package org.example;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class Db {

    private static final String DB_URL = "jdbc:sqlite:kip.db";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm_dd.MM.yy");
    private static final DateTimeFormatter DB_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void init() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement s = c.createStatement()) {

            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA foreign_keys=ON;");
            s.execute("PRAGMA busy_timeout = 5000;");

            s.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    serial_number TEXT,
                    name TEXT NOT NULL,
                    location TEXT,
                    last_verification TEXT,
                    next_verification TEXT,
                    photo_path TEXT,
                    photo_thumb_path TEXT,
                    is_deleted INTEGER DEFAULT 0,
                    version INTEGER DEFAULT 1,
                    created_at TEXT
                );
                """);

            // Миграции для старых баз
            try { s.execute("ALTER TABLE devices ADD COLUMN photo_thumb_path TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN serial_number TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN is_deleted INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN version INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN created_at TEXT"); } catch (SQLException ignored) {}

            // Заполняем created_at для старых записей, у которых его нет
            try {
                s.execute("UPDATE devices SET created_at = datetime('now') WHERE created_at IS NULL");
            } catch (SQLException ignored) {}

            s.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    document_number TEXT,
                    description TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT,
                    custom_fields_json TEXT NOT NULL DEFAULT '{}'
                );
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS document_devices (
                    document_id INTEGER NOT NULL,
                    device_id INTEGER NOT NULL,
                    added_at TEXT NOT NULL,
                    PRIMARY KEY (document_id, device_id),
                    FOREIGN KEY (document_id) REFERENCES documents(id),
                    FOREIGN KEY (device_id) REFERENCES devices(id)
                );
                """);

            // Миграции: поля документа можно расширять без изменения связей с приборами.
            try { s.execute("ALTER TABLE documents ADD COLUMN custom_fields_json TEXT NOT NULL DEFAULT '{}'"); }
            catch (SQLException ignored) {}

            s.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id INTEGER,
                    document_id INTEGER,
                    entity_name TEXT,
                    entity_number TEXT,
                    changed_at TEXT,
                    changed_by TEXT,
                    action TEXT,
                    details TEXT
                );
                """);
            // Миграция: связываем записи журнала как с приборами, так и с документами.
            try { s.execute("ALTER TABLE audit_log ADD COLUMN document_id INTEGER"); }
            catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE audit_log ADD COLUMN entity_name TEXT"); }
            catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE audit_log ADD COLUMN entity_number TEXT"); }
            catch (SQLException ignored) {}

            s.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    login TEXT UNIQUE NOT NULL COLLATE NOCASE,
                    display_name TEXT NOT NULL DEFAULT '',
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'operator',
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                );
                """);
            try { s.execute("ALTER TABLE users ADD COLUMN display_name TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE users ADD COLUMN created_at TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) {}
            s.execute("UPDATE users SET display_name=login WHERE display_name IS NULL OR display_name=''");
            try (PreparedStatement admin = c.prepareStatement("SELECT id FROM users WHERE login='Admin' COLLATE NOCASE");
                 ResultSet rs = admin.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement ins = c.prepareStatement("INSERT INTO users(login,display_name,password_hash,role) VALUES('Admin','Администратор',?,'admin')")) {
                        ins.setString(1, AuthUtil.hash("vfckjgegst"));
                        ins.executeUpdate();
                    }
                } else {
                    try (PreparedStatement upd = c.prepareStatement("UPDATE users SET display_name='Администратор', role='admin' WHERE login='Admin' COLLATE NOCASE")) { upd.executeUpdate(); }
                }
            }
        }
    }

    public static JSONArray getUsersPublic() throws SQLException {
        JSONArray result = new JSONArray();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT id, login, display_name, role FROM users ORDER BY CASE WHEN role='admin' THEN 0 ELSE 1 END, display_name COLLATE NOCASE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.put(new JSONObject().put("id", rs.getLong("id")).put("login", rs.getString("login"))
                    .put("name", rs.getString("display_name")).put("role", rs.getString("role")));
        }
        return result;
    }

    public static JSONObject findUserForLogin(String login) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT id, login, display_name, password_hash, role FROM users WHERE login=? COLLATE NOCASE")) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new JSONObject().put("id", rs.getLong("id")).put("login", rs.getString("login"))
                        .put("name", rs.getString("display_name")).put("passwordHash", rs.getString("password_hash"))
                        .put("role", rs.getString("role"));
            }
        }
    }

    public static long createUser(String name, String password, String createdBy) throws SQLException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 100) throw new SQLException("Имя пользователя обязательно (до 100 символов)");
        if (clean.equalsIgnoreCase("guest") || clean.equalsIgnoreCase("гость")) throw new SQLException("Это имя зарезервировано системой");
        if (password == null || password.length() < 4) throw new SQLException("Пароль должен содержать не менее 4 символов");
        String createdAt = LocalDateTime.now().format(DB_DATE_FORMAT);
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(login,display_name,password_hash,role) VALUES(?,?,?,'operator')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, clean); ps.setString(2, clean); ps.setString(3, AuthUtil.hash(password));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) throw new SQLException("Не удалось создать пользователя");
                    long id = rs.getLong(1);
                    try (PreparedStatement log = c.prepareStatement("INSERT INTO audit_log(entity_name, changed_at, changed_by, action, details) VALUES('Пользователи', ?, ?, 'Создан пользователь', ?)") ) {
                        log.setString(1, createdAt); log.setString(2, createdBy); log.setString(3, "Имя: " + clean); log.executeUpdate();
                    }
                    c.commit(); return id;
                }
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }

    public static boolean deleteUser(long userId, String deletedBy) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                String name;
                String login;
                String role;
                try (PreparedStatement find = c.prepareStatement("SELECT display_name, login, role FROM users WHERE id=?")) {
                    find.setLong(1, userId);
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        name = rs.getString("display_name"); login = rs.getString("login"); role = rs.getString("role");
                    }
                }
                // Основную учётную запись Admin нельзя удалить даже другим администратором.
                if ("admin".equalsIgnoreCase(role) || "Admin".equalsIgnoreCase(login)) { c.rollback(); return false; }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM users WHERE id=?")) {
                    del.setLong(1, userId);
                    if (del.executeUpdate() == 0) { c.rollback(); return false; }
                }
                try (PreparedStatement log = c.prepareStatement("INSERT INTO audit_log(entity_name, changed_at, changed_by, action, details) VALUES('Пользователи', ?, ?, 'Удалён пользователь', ?)")) {
                    log.setString(1, LocalDateTime.now().format(DB_DATE_FORMAT));
                    log.setString(2, deletedBy);
                    log.setString(3, "Имя: " + name + "; логин: " + login);
                    log.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }

    public static List<Device> getAllDevices() throws SQLException {
        List<Device> result = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, version, created_at " +
                             "FROM devices WHERE is_deleted = 0 ORDER BY next_verification ASC")) {
            while (rs.next()) result.add(readDevice(rs));
        }
        return result;
    }

    public static List<Device> getDeletedDevices() throws SQLException {
        List<Device> result = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, version, created_at " +
                             "FROM devices WHERE is_deleted = 1 ORDER BY id DESC")) {
            while (rs.next()) result.add(readDevice(rs));
        }
        return result;
    }

    public static Device getDevice(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, version, created_at " +
                             "FROM devices WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readDevice(rs);
            }
        }
        return null;
    }

    private static Device readDevice(ResultSet rs) throws SQLException {
        return new Device(
                rs.getLong("id"),
                rs.getString("serial_number"),
                rs.getString("name"),
                rs.getString("location"),
                rs.getString("last_verification"),
                rs.getString("next_verification"),
                rs.getString("photo_path"),
                rs.getString("photo_thumb_path"),
                rs.getInt("version"),
                rs.getString("created_at")
        );
    }

    public static long createDevice(Device d, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                long id;
                String now = LocalDateTime.now().format(DB_DATE_FORMAT);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO devices(serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, is_deleted, version, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, d.serialNumber);
                    ps.setString(2, d.name);
                    ps.setString(3, d.location);
                    ps.setString(4, d.lastVerification);
                    ps.setString(5, d.nextVerification);
                    ps.setString(6, d.photoPath);
                    ps.setString(7, d.photoThumbPath);
                    ps.setString(8, now);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                log(c, id, user, "CREATE", "name=" + d.name + ", sn=" + d.serialNumber);
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean updateDevice(Device d, String user) throws SQLException, OptimisticLockException {
        return updateDevice(d, user, false);
    }

    public static boolean updateDevice(Device d, String user, boolean clearPhoto) throws SQLException, OptimisticLockException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                Device old = getDevice(d.id);
                if (old == null) {
                    c.rollback();
                    return false;
                }

                if (d.version > 0 && old.version != d.version) {
                    c.rollback();
                    throw new OptimisticLockException(d.id, d.version, old.version);
                }

                String finalPhotoPath;
                String finalPhotoThumb;

                if (clearPhoto) {
                    finalPhotoPath = null;
                    finalPhotoThumb = null;
                } else {
                    finalPhotoPath = (d.photoPath == null) ? old.photoPath : d.photoPath;
                    finalPhotoThumb = (d.photoThumbPath == null) ? old.photoThumbPath : d.photoThumbPath;
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE devices SET serial_number=?, name=?, location=?, last_verification=?, " +
                                "next_verification=?, photo_path=?, photo_thumb_path=?, version = version + 1 WHERE id=? AND version=?")) {
                    ps.setString(1, d.serialNumber);
                    ps.setString(2, d.name);
                    ps.setString(3, d.location);
                    ps.setString(4, d.lastVerification);
                    ps.setString(5, d.nextVerification);
                    ps.setString(6, finalPhotoPath);
                    ps.setString(7, finalPhotoThumb);
                    ps.setLong(8, d.id);
                    ps.setInt(9, old.version);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        c.rollback();
                        throw new OptimisticLockException(d.id, d.version, old.version);
                    }
                }

                StringBuilder details = new StringBuilder();
                appendIfChanged(details, "serial_number", old.serialNumber, d.serialNumber);
                appendIfChanged(details, "name", old.name, d.name);
                appendIfChanged(details, "location", old.location, d.location);
                appendIfChangedDate(details, "last_verification", old.lastVerification, d.lastVerification);
                appendIfChangedDate(details, "next_verification", old.nextVerification, d.nextVerification);

                if (clearPhoto) {
                    details.append(details.length() > 0 ? "; " : "").append("photo: удалено");
                }

                log(c, d.id, user, "UPDATE", details.length() == 0 ? "no changes" : details.toString());
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean deleteDevice(long id, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE devices SET is_deleted = 1, version = version + 1 WHERE id=?")) {
                    ps.setLong(1, id);
                    int n = ps.executeUpdate();
                    if (n == 0) {
                        c.rollback();
                        return false;
                    }
                }
                log(c, id, user, "DELETE", "Перемещено в корзину");
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean restoreDevice(long id, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE devices SET is_deleted = 0, version = version + 1 WHERE id=?")) {
                    ps.setLong(1, id);
                    int n = ps.executeUpdate();
                    if (n == 0) {
                        c.rollback();
                        return false;
                    }
                }
                log(c, id, user, "RESTORE", "Восстановлено из корзины");
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static void updatePhotoPaths(long id, String photoPath, String thumbPath, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE devices SET photo_path=?, photo_thumb_path=?, version = version + 1 WHERE id=?")) {
                    ps.setString(1, photoPath);
                    ps.setString(2, thumbPath);
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
                String details = photoPath;
                if (thumbPath != null) details += " (+thumb)";
                log(c, id, user, "PHOTO", details);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static List<String[]> getAudit(int limit) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT a.changed_at, a.changed_by, a.action, a.details, " +
                             "COALESCE(doc.title, a.entity_name, d.name), " +
                             "COALESCE(doc.document_number, a.entity_number, d.serial_number) " +
                             "FROM audit_log a " +
                             "LEFT JOIN devices d ON a.device_id = d.id " +
                             "LEFT JOIN documents doc ON a.document_id = doc.id " +
                             "ORDER BY a.id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{
                            rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)
                    });
                }
            }
        }
        return rows;
    }


    // ================= ДОКУМЕНТЫ =================

    public static JSONArray getDocuments() throws SQLException {
        JSONArray result = new JSONArray();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("""
                 SELECT d.id, d.title, d.document_number, d.description, d.created_at,
                        d.updated_at, d.custom_fields_json, COUNT(v.id) AS device_count
                 FROM documents d
                 LEFT JOIN document_devices dd ON dd.document_id = d.id
                 LEFT JOIN devices v ON v.id = dd.device_id AND v.is_deleted = 0
                 GROUP BY d.id
                 ORDER BY d.created_at DESC, d.id DESC
             """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(documentJson(rs, false));
            }
        }
        return result;
    }

    public static JSONObject getDocument(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("""
                 SELECT d.id, d.title, d.document_number, d.description, d.created_at,
                        d.updated_at, d.custom_fields_json,
                        (SELECT COUNT(*) FROM document_devices x JOIN devices v ON v.id=x.device_id WHERE x.document_id=d.id AND v.is_deleted=0) AS device_count
                 FROM documents d WHERE d.id=?
             """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JSONObject doc = documentJson(rs, true);
                JSONArray devices = new JSONArray();
                try (PreparedStatement dp = c.prepareStatement("""
                    SELECT v.id, v.serial_number, v.name, v.location, v.last_verification,
                           v.next_verification, v.photo_path, v.photo_thumb_path, v.version, v.created_at
                    FROM document_devices dd
                    JOIN devices v ON v.id=dd.device_id
                    WHERE dd.document_id=? AND v.is_deleted=0
                    ORDER BY v.name COLLATE NOCASE
                """)) {
                    dp.setLong(1, id);
                    try (ResultSet dr = dp.executeQuery()) {
                        while (dr.next()) {
                            Device d = readDevice(dr);
                            devices.put(new JSONObject()
                                .put("id", d.id)
                                .put("name", d.name == null ? "" : d.name)
                                .put("serialNumber", d.serialNumber == null ? "" : d.serialNumber)
                                .put("location", d.location == null ? "" : d.location)
                                .put("lastVerification", d.lastVerification == null ? "" : d.lastVerification)
                                .put("nextVerification", d.nextVerification == null ? "" : d.nextVerification)
                                .put("photoPath", d.photoPath == null ? JSONObject.NULL : d.photoPath)
                                .put("photoThumbPath", d.photoThumbPath == null ? JSONObject.NULL : d.photoThumbPath)
                                .put("version", d.version)
                                .put("createdAt", d.createdAt == null ? "" : d.createdAt));
                        }
                    }
                }
                doc.put("devices", devices);
                return doc;
            }
        }
    }

    private static JSONObject documentJson(ResultSet rs, boolean includeFields) throws SQLException {
        JSONObject o = new JSONObject()
            .put("id", rs.getLong("id"))
            .put("title", rs.getString("title"))
            .put("documentNumber", rs.getString("document_number") == null ? "" : rs.getString("document_number"))
            .put("description", rs.getString("description") == null ? "" : rs.getString("description"))
            .put("createdAt", rs.getString("created_at"))
            .put("updatedAt", rs.getString("updated_at") == null ? "" : rs.getString("updated_at"))
            .put("deviceCount", rs.getInt("device_count"));
        if (includeFields) {
            try { o.put("customFields", new JSONObject(rs.getString("custom_fields_json"))); }
            catch (Exception e) { o.put("customFields", new JSONObject()); }
        }
        return o;
    }

    public static long createDocument(String title, String number, String description,
                                      JSONObject customFields, String user) throws SQLException {
        String now = LocalDateTime.now().format(DB_DATE_FORMAT);
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO documents(title, document_number, description, created_at, updated_at, custom_fields_json)
                 VALUES(?, ?, ?, ?, ?, ?)
             """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, title);
                ps.setString(2, number);
                ps.setString(3, description);
                ps.setString(4, now);
                ps.setString(5, now);
                ps.setString(6, customFields == null ? "{}" : customFields.toString());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) throw new SQLException("Не удалось создать документ");
                    long id = rs.getLong(1);
                    logDocument(c, id, null, user, "Создан документ",
                            documentLabel(title, number) + (description == null || description.isBlank() ? "" : "; описание: " + description));
                    c.commit();
                    return id;
                }
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean updateDocument(long id, String title, String number, String description,
                                         JSONObject customFields, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                String oldTitle;
                String oldNumber;
                String oldDescription;
                try (PreparedStatement get = c.prepareStatement(
                        "SELECT title, document_number, description FROM documents WHERE id=?")) {
                    get.setLong(1, id);
                    try (ResultSet rs = get.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        oldTitle = rs.getString(1);
                        oldNumber = rs.getString(2);
                        oldDescription = rs.getString(3);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                     UPDATE documents SET title=?, document_number=?, description=?, updated_at=?, custom_fields_json=?
                     WHERE id=?
                 """)) {
                    ps.setString(1, title);
                    ps.setString(2, number);
                    ps.setString(3, description);
                    ps.setString(4, LocalDateTime.now().format(DB_DATE_FORMAT));
                    ps.setString(5, customFields == null ? "{}" : customFields.toString());
                    ps.setLong(6, id);
                    if (ps.executeUpdate() == 0) { c.rollback(); return false; }
                }
                StringBuilder changes = new StringBuilder();
                appendIfChanged(changes, "название", oldTitle, title);
                appendIfChanged(changes, "номер", oldNumber, number);
                appendIfChanged(changes, "описание", oldDescription, description);
                if (changes.length() == 0) changes.append("Сохранены дополнительные поля");
                logDocument(c, id, null, user, "Изменен документ", changes.toString());
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean deleteDocument(long id, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                String title;
                String number;
                try (PreparedStatement get = c.prepareStatement(
                        "SELECT title, document_number FROM documents WHERE id=?")) {
                    get.setLong(1, id);
                    try (ResultSet rs = get.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        title = rs.getString(1);
                        number = rs.getString(2);
                    }
                }
                int linked;
                try (PreparedStatement count = c.prepareStatement(
                        "SELECT COUNT(*) FROM document_devices WHERE document_id=?")) {
                    count.setLong(1, id);
                    try (ResultSet rs = count.executeQuery()) { linked = rs.next() ? rs.getInt(1) : 0; }
                }
                logDocument(c, id, null, user, "Удален документ",
                        documentLabel(title, number) + "; удалено связей с приборами: " + linked);
                try (PreparedStatement links = c.prepareStatement("DELETE FROM document_devices WHERE document_id=?")) {
                    links.setLong(1, id);
                    links.executeUpdate();
                }
                try (PreparedStatement doc = c.prepareStatement("DELETE FROM documents WHERE id=?")) {
                    doc.setLong(1, id);
                    boolean deleted = doc.executeUpdate() > 0;
                    c.commit();
                    return deleted;
                }
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean attachDevice(long documentId, long deviceId, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                String docTitle;
                String docNumber;
                try (PreparedStatement check = c.prepareStatement(
                        "SELECT title, document_number FROM documents WHERE id=?")) {
                    check.setLong(1, documentId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Документ не найден");
                        docTitle = rs.getString(1);
                        docNumber = rs.getString(2);
                    }
                }
                String deviceName;
                String serial;
                try (PreparedStatement check = c.prepareStatement(
                        "SELECT name, serial_number FROM devices WHERE id=? AND is_deleted=0")) {
                    check.setLong(1, deviceId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Прибор не найден или находится в корзине");
                        deviceName = rs.getString(1);
                        serial = rs.getString(2);
                    }
                }
                int inserted;
                try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO document_devices(document_id, device_id, added_at)
                    VALUES(?, ?, ?)
                """)) {
                    ps.setLong(1, documentId);
                    ps.setLong(2, deviceId);
                    ps.setString(3, LocalDateTime.now().format(DB_DATE_FORMAT));
                    inserted = ps.executeUpdate();
                }
                if (inserted > 0) {
                    logDocument(c, documentId, deviceId, user, "Прибор добавлен в документ",
                            documentLabel(docTitle, docNumber) + "; прибор: " + deviceName +
                                    (serial == null || serial.isBlank() ? "" : "; зав. № " + serial));
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static boolean detachDevice(long documentId, long deviceId, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                String docTitle;
                String docNumber;
                try (PreparedStatement get = c.prepareStatement(
                        "SELECT title, document_number FROM documents WHERE id=?")) {
                    get.setLong(1, documentId);
                    try (ResultSet rs = get.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        docTitle = rs.getString(1);
                        docNumber = rs.getString(2);
                    }
                }
                String deviceName = "Прибор ID " + deviceId;
                String serial = null;
                try (PreparedStatement get = c.prepareStatement("SELECT name, serial_number FROM devices WHERE id=?")) {
                    get.setLong(1, deviceId);
                    try (ResultSet rs = get.executeQuery()) {
                        if (rs.next()) { deviceName = rs.getString(1); serial = rs.getString(2); }
                    }
                }
                int deleted;
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM document_devices WHERE document_id=? AND device_id=?")) {
                    ps.setLong(1, documentId);
                    ps.setLong(2, deviceId);
                    deleted = ps.executeUpdate();
                }
                if (deleted > 0) {
                    logDocument(c, documentId, deviceId, user, "Прибор удален из документа",
                            documentLabel(docTitle, docNumber) + "; прибор: " + deviceName +
                                    (serial == null || serial.isBlank() ? "" : "; зав. № " + serial));
                }
                c.commit();
                return deleted > 0;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static String documentLabel(String title, String number) {
        return "Документ: " + (title == null ? "" : title) +
                (number == null || number.isBlank() ? "" : "; № " + number);
    }

    private static void logDocument(Connection c, long documentId, Long deviceId, String user,
                                    String action, String details) throws SQLException {
        String title = null;
        String number = null;
        try (PreparedStatement get = c.prepareStatement(
                "SELECT title, document_number FROM documents WHERE id=?")) {
            get.setLong(1, documentId);
            try (ResultSet rs = get.executeQuery()) {
                if (rs.next()) { title = rs.getString(1); number = rs.getString(2); }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log(device_id, document_id, entity_name, entity_number, changed_at, changed_by, action, details) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            if (deviceId == null) ps.setNull(1, Types.INTEGER); else ps.setLong(1, deviceId);
            ps.setLong(2, documentId);
            ps.setString(3, title);
            ps.setString(4, number);
            ps.setString(5, LocalDateTime.now().format(TS));
            ps.setString(6, user);
            ps.setString(7, action);
            ps.setString(8, details);
            ps.executeUpdate();
        }
    }

    private static void log(Connection c, long deviceId, String user,
                            String action, String details) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log(device_id, changed_at, changed_by, action, details) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, deviceId);
            ps.setString(2, LocalDateTime.now().format(TS));
            ps.setString(3, user);
            ps.setString(4, action);
            ps.setString(5, details);
            ps.executeUpdate();
        }
    }

    private static void appendIfChanged(StringBuilder sb, String field, String oldV, String newV) {
        String o = oldV == null ? "" : oldV;
        String n = newV == null ? "" : newV;
        if (!o.equals(n)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(field).append(": ").append(o).append(" -> ").append(n);
        }
    }

    private static void appendIfChangedDate(StringBuilder sb, String field, String oldV, String newV) {
        String o = oldV == null ? "" : oldV;
        String n = newV == null ? "" : newV;
        if (!o.equals(n)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(field).append(": ").append(formatDateStr(o)).append(" -> ").append(formatDateStr(n));
        }
    }

    private static String formatDateStr(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "";
        try {
            LocalDate date = LocalDate.parse(dateStr);
            return date.format(DateTimeFormatter.ofPattern("dd.MM.yy"));
        } catch (Exception e) {
            return dateStr;
        }
    }
}
