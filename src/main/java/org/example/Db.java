package org.example;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class Db {

    // Сначала ищем существующую базу рядом с рабочей папкой и приложением.
    // Это предотвращает создание новой пустой kip.db при запуске ярлыком из другой папки.
    private static final Path DB_PATH = resolveDatabasePath();
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH.toString().replace("\\", "/");

    public static Path getDatabasePath() { return DB_PATH; }
    public static Connection openConnection() throws SQLException { return DriverManager.getConnection(DB_URL); }

    private static Path resolveDatabasePath() {
        String configured = System.getProperty("kip.db.path");
        if (configured == null || configured.isBlank()) configured = System.getenv("KIP_DB_PATH");
        if (configured != null && !configured.isBlank()) return Paths.get(configured).toAbsolutePath().normalize();

        Set<Path> bases = new LinkedHashSet<>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path code = null;
        try {
            URI location = Db.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            code = Paths.get(location).toAbsolutePath().normalize();
            if (Files.isRegularFile(code)) code = code.getParent();
            addAncestors(bases, code); // сначала папка самого приложения
        } catch (Exception ignored) { }
        addAncestors(bases, cwd);

        // Если найдено несколько kip.db (например, после запуска из разных папок),
        // выбираем наиболее заполненный по размеру файл. Ничего не удаляем и не копируем.
        Path best = null;
        long bestSize = -1;
        for (Path base : bases) {
            Path candidate = base.resolve("kip.db");
            if (!Files.isRegularFile(candidate)) continue;
            try {
                long size = Files.size(candidate);
                Path wal = candidate.resolveSibling(candidate.getFileName() + "-wal");
                if (Files.isRegularFile(wal)) size += Files.size(wal);
                if (size > bestSize) { best = candidate; bestSize = size; }
            } catch (Exception ignored) { }
        }
        if (best != null) return best;

        // Если база ещё не создана, кладём её в корень проекта (при наличии pom.xml),
        // иначе рядом с приложением, а не в случайную рабочую папку процесса.
        for (Path base : bases) if (Files.isRegularFile(base.resolve("pom.xml"))) return base.resolve("kip.db");
        try {
            Path codePath = Paths.get(Db.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(codePath)) codePath = codePath.getParent();
            return codePath.resolve("kip.db");
        } catch (Exception ignored) { return cwd.resolve("kip.db"); }
    }

    private static void addAncestors(Set<Path> result, Path start) {
        Path p = start;
        for (int i = 0; p != null && i < 8; i++, p = p.getParent()) result.add(p);
    }
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm_dd.MM.yy");
    private static final DateTimeFormatter DB_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void init() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement s = c.createStatement()) {

            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA foreign_keys=ON;");
            s.execute("PRAGMA busy_timeout = 5000;");

            // Для новых баз поля дат создаются с типом DATE.
            s.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    serial_number TEXT,
                    name TEXT NOT NULL,
                    location TEXT,
                    last_verification DATE,
                    next_verification DATE,
                    photo_path TEXT,
                    photo_thumb_path TEXT,
                    is_deleted INTEGER DEFAULT 0,
                    version INTEGER DEFAULT 1,
                    created_at TEXT
                );
                """);

            // Миграции для старых баз (добавление новых колонок).
            try { s.execute("ALTER TABLE devices ADD COLUMN photo_thumb_path TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN serial_number TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN is_deleted INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN version INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN created_at TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE devices ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}'"); } catch (SQLException ignored) {}

            // Заполняем created_at для старых записей, у которых его нет
            try {
                s.execute("UPDATE devices SET created_at = datetime('now') WHERE created_at IS NULL");
            } catch (SQLException ignored) {}

            // ===== Миграция типов last_verification / next_verification на DATE =====
            // SQLite не поддерживает ALTER COLUMN TYPE, поэтому пересоздаём таблицу.
            migrateDateColumnsToDateType(s);

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
                CREATE TABLE IF NOT EXISTS device_movements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id INTEGER NOT NULL,
                    event_date DATE NOT NULL,
                    event_type TEXT NOT NULL,
                    from_place TEXT,
                    to_place TEXT,
                    note TEXT,
                    created_by TEXT,
                    created_at TEXT NOT NULL
                );
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_device_movements_created ON device_movements(event_date DESC, created_at DESC, id DESC)");

            s.execute("""
                CREATE TABLE IF NOT EXISTS device_document_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id INTEGER NOT NULL,
                    document_id INTEGER,
                    document_title TEXT NOT NULL,
                    document_number TEXT,
                    event_type TEXT NOT NULL,
                    event_date DATE NOT NULL,
                    created_by TEXT,
                    created_at TEXT NOT NULL
                );
                """);

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
            // Системная учётная запись гостя существует в каждой БД.
            // Вход гостя остаётся без пароля, а случайный хеш не задаёт ему пароль для обычной авторизации.
            try (PreparedStatement guest = c.prepareStatement("SELECT id FROM users WHERE login='guest' COLLATE NOCASE");
                 ResultSet rs = guest.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement ins = c.prepareStatement("INSERT INTO users(login,display_name,password_hash,role) VALUES('guest','Гость',?,'guest')")) {
                        ins.setString(1, AuthUtil.hash(java.util.UUID.randomUUID().toString()));
                        ins.executeUpdate();
                    }
                } else {
                    try (PreparedStatement upd = c.prepareStatement("UPDATE users SET display_name='Гость', role='guest' WHERE login='guest' COLLATE NOCASE")) { upd.executeUpdate(); }
                }
            }
        }
        // Версия общего набора приборов для HTTP ETag. Триггеры отслеживают
        // любые INSERT/UPDATE/DELETE, включая изменения из других методов приложения.
        try (Connection meta = DriverManager.getConnection(DB_URL);
             Statement ms = meta.createStatement()) {
            ms.execute("CREATE TABLE IF NOT EXISTS app_meta (meta_key TEXT PRIMARY KEY, meta_value INTEGER NOT NULL)");
            ms.execute("INSERT OR IGNORE INTO app_meta(meta_key, meta_value) VALUES ('devices_revision', 1)");
            ms.execute("""
                CREATE TRIGGER IF NOT EXISTS devices_revision_insert
                AFTER INSERT ON devices BEGIN
                    UPDATE app_meta SET meta_value = meta_value + 1 WHERE meta_key = 'devices_revision';
                END;
                """);
            ms.execute("""
                CREATE TRIGGER IF NOT EXISTS devices_revision_update
                AFTER UPDATE ON devices BEGIN
                    UPDATE app_meta SET meta_value = meta_value + 1 WHERE meta_key = 'devices_revision';
                END;
                """);
            ms.execute("""
                CREATE TRIGGER IF NOT EXISTS devices_revision_delete
                AFTER DELETE ON devices BEGIN
                    UPDATE app_meta SET meta_value = meta_value + 1 WHERE meta_key = 'devices_revision';
                END;
                """);
        }
        //importSampleDevicesIfEmpty();
        //    migrateLegacyMovementHistory();
        //seedCurrentDocumentHistory();
    }

    /** Текущая ревизия списка приборов; меняется SQLite-триггерами при любой модификации. */
    public static long getDevicesRevision() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT meta_value FROM app_meta WHERE meta_key='devices_revision'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 1L;
        }
    }

    /**
     * Меняет тип колонок last_verification и next_verification на DATE.
     * SQLite не поддерживает ALTER COLUMN TYPE, поэтому таблица пересоздаётся.
     * Миграция идемпотентна: если колонки уже имеют тип DATE — ничего не делает.
     */
    private static void migrateDateColumnsToDateType(Statement s) throws SQLException {
        boolean needMigration = false;
        try (ResultSet rs = s.executeQuery("PRAGMA table_info(devices)")) {
            while (rs.next()) {
                String colName = rs.getString("name");
                String colType = rs.getString("type");
                if (("last_verification".equalsIgnoreCase(colName)
                        || "next_verification".equalsIgnoreCase(colName))
                        && colType != null
                        && !"DATE".equalsIgnoreCase(colType)) {
                    needMigration = true;
                    break;
                }
            }
        }

        if (!needMigration) return;

        System.out.println("Миграция: смена типа last_verification / next_verification на DATE...");

        try { s.execute("PRAGMA foreign_keys=OFF;"); } catch (SQLException ignored) {}
        s.execute("BEGIN TRANSACTION;");
        try {
            s.execute("ALTER TABLE devices RENAME TO devices_old;");

            s.execute("""
                CREATE TABLE devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    serial_number TEXT,
                    name TEXT NOT NULL,
                    location TEXT,
                    last_verification DATE,
                    next_verification DATE,
                    photo_path TEXT,
                    photo_thumb_path TEXT,
                    is_deleted INTEGER DEFAULT 0,
                    version INTEGER DEFAULT 1,
                    created_at TEXT,
                    details_json TEXT NOT NULL DEFAULT '{}'
                );
                """);

            // Копируем данные. Пустые строки превращаем в NULL,
            // чтобы новый тип DATE работал корректно (ORDER BY ... IS NULL и т.д.).
            s.execute("""
                INSERT INTO devices (
                    id, serial_number, name, location, last_verification, next_verification,
                    photo_path, photo_thumb_path, is_deleted, version, created_at, details_json
                )
                SELECT
                    id, serial_number, name, location,
                    CASE WHEN last_verification IS NULL OR TRIM(last_verification) = '' THEN NULL
                         ELSE last_verification END,
                    CASE WHEN next_verification IS NULL OR TRIM(next_verification) = '' THEN NULL
                         ELSE next_verification END,
                    photo_path, photo_thumb_path, is_deleted, version, created_at, details_json
                FROM devices_old;
                """);

            s.execute("DROP TABLE devices_old;");
            s.execute("COMMIT;");
            System.out.println("Миграция типов дат выполнена успешно.");
        } catch (SQLException e) {
            try { s.execute("ROLLBACK;"); } catch (SQLException ignored) {}
            throw e;
        } finally {
            try { s.execute("PRAGMA foreign_keys=ON;"); } catch (SQLException ignored) {}
        }
    }

    /**
     * Записывает строку как дату или NULL. Пустые/пробельные значения → NULL,
     * чтобы DATE-колонка вела себя корректно (в т.ч. в ORDER BY ... IS NULL).
     */
    private static void setDateOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.trim().isEmpty()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.trim());
        }
    }

    // Переносим старый текстовый столбец «Движение» в отдельные события, сохраняя исходные формулировки.
    private static void migrateLegacyMovementHistory() throws SQLException {
        try (Connection c=DriverManager.getConnection(DB_URL);
             PreparedStatement ps=c.prepareStatement("SELECT id,details_json FROM devices WHERE details_json IS NOT NULL AND details_json <> '{}' ");
             ResultSet rs=ps.executeQuery()) {
            while (rs.next()) {
                long deviceId=rs.getLong(1);
                JSONObject details;
                try { details=new JSONObject(rs.getString(2)); } catch(Exception ex) { continue; }
                String legacy=details.optString("movement","").trim();
                if (legacy.isEmpty() || "—".equals(legacy)) continue;
                for (String part : legacy.split("\\s*;\\s*")) {
                    String entry=part.trim(); if(entry.isEmpty()) continue;
                    String date=LocalDate.now().toString();
                    String description=entry;
                    java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(\\d{2})\\.(\\d{2})\\.(\\d{4})\\s*:\\s*(.*)$").matcher(entry);
                    if(m.matches()) { date=m.group(3)+"-"+m.group(2)+"-"+m.group(1); description=m.group(4).trim(); }
                    String type=description;
                    String from="",to="";
                    java.util.regex.Matcher removed=java.util.regex.Pattern.compile("Снят с\\s+([^;]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(description);
                    java.util.regex.Matcher installed=java.util.regex.Pattern.compile("Установлен на\\s+([^;]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(description);
                    if(removed.find()) { type="Снят с позиции"; from=removed.group(1).trim(); }
                    else if(installed.find()) { type="Установлен"; to=installed.group(1).trim(); }
                    else if(description.toLowerCase().contains("поверк")) type=description.toLowerCase().contains("после")?"Возвращён после поверки":"Передан на поверку";
                    else if(description.toLowerCase().contains("калибров")) type=description.toLowerCase().contains("после")?"Возвращён после калибровки":"Передан на калибровку";
                    else if(description.toLowerCase().contains("ремонт")) type=description.toLowerCase().contains("после")?"Возвращён из ремонта":"Передан в ремонт";
                    try (PreparedStatement exists=c.prepareStatement("SELECT 1 FROM device_movements WHERE device_id=? AND note=? LIMIT 1")) {
                        exists.setLong(1,deviceId); exists.setString(2,entry);
                        try(ResultSet er=exists.executeQuery()) { if(er.next()) continue; }
                    }
                    try (PreparedStatement ins=c.prepareStatement("INSERT INTO device_movements(device_id,event_date,event_type,from_place,to_place,note,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                        ins.setLong(1,deviceId); ins.setString(2,date); ins.setString(3,type); ins.setString(4,from); ins.setString(5,to); ins.setString(6,entry); ins.setString(7,"Импорт из старой колонки"); ins.setString(8,LocalDateTime.now().format(DB_DATE_FORMAT)); ins.executeUpdate();
                    }
                }
            }
        }
    }

    // Для уже существующих связей с документами создаём начальные записи истории.
    private static void seedCurrentDocumentHistory() throws SQLException {
        try (Connection c=DriverManager.getConnection(DB_URL); Statement st=c.createStatement()) {
            // Сначала переносим уже накопленные операции включения/исключения из общего журнала.
            st.executeUpdate("INSERT INTO device_document_history(device_id,document_id,document_title,document_number,event_type,event_date,created_by,created_at) " +
                    "SELECT a.device_id,a.document_id,COALESCE(a.entity_name,'Документ'),COALESCE(a.entity_number,'')," +
                    "CASE WHEN a.action='Прибор добавлен в документ' THEN 'Включён в документ' ELSE 'Исключён из документа' END," +
                    "'20'||substr(a.changed_at,13,2)||'-'||substr(a.changed_at,10,2)||'-'||substr(a.changed_at,7,2),a.changed_by,datetime('now') " +
                    "FROM audit_log a WHERE a.device_id IS NOT NULL AND a.document_id IS NOT NULL " +
                    "AND a.action IN ('Прибор добавлен в документ','Прибор удален из документа') " +
                    "AND NOT EXISTS(SELECT 1 FROM device_document_history h WHERE h.device_id=a.device_id AND h.document_id=a.document_id AND h.event_type=CASE WHEN a.action='Прибор добавлен в документ' THEN 'Включён в документ' ELSE 'Исключён из документа' END AND h.event_date='20'||substr(a.changed_at,13,2)||'-'||substr(a.changed_at,10,2)||'-'||substr(a.changed_at,7,2))");
            // Для связей, которые уже существуют, но не имеют записи журнала, создаём начальное событие.
            st.executeUpdate("INSERT INTO device_document_history(device_id,document_id,document_title,document_number,event_type,event_date,created_by,created_at) " +
                    "SELECT dd.device_id,d.id,d.title,d.document_number,'Включён в документ',substr(dd.added_at,1,10),'Миграция',datetime('now') " +
                    "FROM document_devices dd JOIN documents d ON d.id=dd.document_id " +
                    "WHERE NOT EXISTS(SELECT 1 FROM device_document_history h WHERE h.device_id=dd.device_id AND h.document_id=dd.document_id AND h.event_type='Включён в документ')");
        }
    }

    private static void importSampleDevicesIfEmpty() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM devices")) {
            if (rs.next() && rs.getLong(1) > 0) return;
        }
        try (java.io.InputStream in = Db.class.getResourceAsStream("/devices-test-data.json")) {
            if (in == null) return;
            JSONArray rows = new JSONArray(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            try (Connection c = DriverManager.getConnection(DB_URL); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO devices(serial_number,name,location,last_verification,next_verification,is_deleted,version,created_at,details_json) VALUES(?,?,?,?,?,0,1,?,?)")) {
                c.setAutoCommit(false);
                String now = LocalDateTime.now().format(DB_DATE_FORMAT);
                for (int i=0; i<rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    ps.setString(1,row.optString("serialNumber","")); ps.setString(2,row.optString("name","Прибор"));
                    ps.setString(3,row.optString("location",""));
                    setDateOrNull(ps, 4, row.optString("lastVerification",""));
                    setDateOrNull(ps, 5, row.optString("nextVerification",""));
                    ps.setString(6,now);
                    ps.setString(7,row.getJSONObject("details").toString()); ps.addBatch();
                }
                ps.executeBatch(); c.commit();
            } catch (Exception e) { throw new SQLException("Не удалось загрузить тестовые приборы",e); }
        } catch (java.io.IOException e) { throw new SQLException("Не удалось прочитать тестовые данные",e); }
    }

    public static JSONArray getUsersPublic() throws SQLException {
        JSONArray result = new JSONArray();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT id, login, display_name, role FROM users ORDER BY CASE WHEN role='admin' THEN 0 WHEN role='guest' THEN 1 ELSE 2 END, display_name COLLATE NOCASE");
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
                if ("admin".equalsIgnoreCase(role) || "Admin".equalsIgnoreCase(login)
                        || "guest".equalsIgnoreCase(role) || "guest".equalsIgnoreCase(login)) { c.rollback(); return false; }
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
                     // Сортировка по умолчанию: ближайшие даты следующей поверки — сверху,
                     // пустые (NULL) — в самом конце. Это гарантирует, что пользователь
                     // сразу увидит приборы, требующие внимания.
                     "SELECT id, serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, version, created_at, details_json " +
                             "FROM devices WHERE is_deleted = 0 " +
                             "ORDER BY next_verification IS NULL, next_verification ASC, id ASC")) {
            while (rs.next()) result.add(readDevice(rs));
        }
        return result;
    }

    public static List<Device> getDeletedDevices() throws SQLException {
        List<Device> result = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, version, created_at, details_json " +
                             "FROM devices WHERE is_deleted = 1 ORDER BY id DESC")) {
            while (rs.next()) result.add(readDevice(rs));
        }
        return result;
    }

    public static Device getDevice(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, version, created_at, details_json " +
                             "FROM devices WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readDevice(rs);
            }
        }
        return null;
    }

    private static Device readDevice(ResultSet rs) throws SQLException {
        Device d = new Device(rs.getLong("id"), rs.getString("serial_number"), rs.getString("name"),
                rs.getString("location"), rs.getString("last_verification"), rs.getString("next_verification"),
                rs.getString("photo_path"), rs.getString("photo_thumb_path"), rs.getInt("version"), rs.getString("created_at"));
        d.detailsJson = rs.getString("details_json");
        return d;
    }

    public static long createDevice(Device d, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                long id;
                String now = LocalDateTime.now().format(DB_DATE_FORMAT);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO devices(serial_number, name, location, last_verification, next_verification, photo_path, photo_thumb_path, is_deleted, version, created_at, details_json) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, d.serialNumber);
                    ps.setString(2, d.name);
                    ps.setString(3, d.location);
                    setDateOrNull(ps, 4, d.lastVerification);
                    setDateOrNull(ps, 5, d.nextVerification);
                    ps.setString(6, d.photoPath);
                    ps.setString(7, d.photoThumbPath);
                    ps.setString(8, now);
                    ps.setString(9, d.detailsJson == null ? "{}" : d.detailsJson);
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

                String effectiveDetailsJson = (d.detailsJson == null || d.detailsJson.isBlank() || "{}".equals(d.detailsJson.trim()))
                        ? (old.detailsJson == null ? "{}" : old.detailsJson) : d.detailsJson;

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE devices SET serial_number=?, name=?, location=?, last_verification=?, " +
                                "next_verification=?, photo_path=?, photo_thumb_path=?, details_json=?, version = version + 1 WHERE id=? AND version=?")) {
                    ps.setString(1, d.serialNumber);
                    ps.setString(2, d.name);
                    ps.setString(3, d.location);
                    setDateOrNull(ps, 4, d.lastVerification);
                    setDateOrNull(ps, 5, d.nextVerification);
                    ps.setString(6, finalPhotoPath);
                    ps.setString(7, finalPhotoThumb);
                    ps.setString(8, effectiveDetailsJson);
                    ps.setLong(9, d.id);
                    ps.setInt(10, old.version);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        c.rollback();
                        throw new OptimisticLockException(d.id, d.version, old.version);
                    }
                }

                // Журналируем не только базовые колонки, но и ВСЕ дополнительные поля,
                // которые хранятся в details_json (шкала, тип датчика, класс точности и т.д.).
                StringBuilder details = new StringBuilder();
                appendIfChanged(details, "Заводской номер", old.serialNumber, d.serialNumber);
                appendIfChanged(details, "Наименование", old.name, d.name);
                appendIfChanged(details, "Место установки", old.location, d.location);
                appendIfChangedDate(details, "Дата последней поверки", old.lastVerification, d.lastVerification);
                appendIfChangedDate(details, "Дата следующей поверки", old.nextVerification, d.nextVerification);
                appendIfChanged(details, "Фото", old.photoPath, finalPhotoPath);
                appendIfChanged(details, "Миниатюра фото", old.photoThumbPath, finalPhotoThumb);
                appendChangedJsonFields(details, old.detailsJson, effectiveDetailsJson);

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
                           v.next_verification, v.photo_path, v.photo_thumb_path, v.version, v.created_at, v.details_json
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
                    try (PreparedStatement hist=c.prepareStatement("INSERT INTO device_document_history(device_id,document_id,document_title,document_number,event_type,event_date,created_by) VALUES(?,?,?,?,?,?,?)")) {
                        hist.setLong(1,deviceId); hist.setLong(2,documentId); hist.setString(3,docTitle); hist.setString(4,docNumber); hist.setString(5,"Включён в документ"); hist.setString(6,LocalDate.now().toString()); hist.setString(7,user); hist.executeUpdate();
                    }
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
                    try (PreparedStatement hist=c.prepareStatement("INSERT INTO device_document_history(device_id,document_id,document_title,document_number,event_type,event_date,created_by) VALUES(?,?,?,?,?,?,?)")) {
                        hist.setLong(1,deviceId); hist.setLong(2,documentId); hist.setString(3,docTitle); hist.setString(4,docNumber); hist.setString(5,"Исключён из документа"); hist.setString(6,LocalDate.now().toString()); hist.setString(7,user); hist.executeUpdate();
                    }
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

    public static JSONArray getMovements(String search, int limit) throws SQLException {
        JSONArray result = new JSONArray();
        String q = search == null ? "" : search.trim().toLowerCase();
        String sql = """
                SELECT m.id, m.device_id, d.name, d.serial_number, m.event_date, m.event_type,
                       m.from_place, m.to_place, m.note, m.created_by, m.created_at
                FROM device_movements m
                LEFT JOIN devices d ON d.id = m.device_id
                WHERE (? = '' OR
                       lower(coalesce(d.name,'')) LIKE ? OR
                       lower(coalesce(d.serial_number,'')) LIKE ? OR
                       lower(coalesce(m.event_type,'')) LIKE ? OR
                       lower(coalesce(m.from_place,'')) LIKE ? OR
                       lower(coalesce(m.to_place,'')) LIKE ? OR
                       lower(coalesce(m.note,'')) LIKE ? OR
                       lower(coalesce(m.created_by,'')) LIKE ? OR
                       cast(m.device_id AS TEXT) LIKE ?)
                ORDER BY m.event_date DESC, m.created_at DESC, m.id DESC
                """ + (limit > 0 ? " LIMIT ?" : "");
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(sql)) {
            String like = "%" + q + "%";
            ps.setString(1, q);
            for (int i = 2; i <= 9; i++) ps.setString(i, like);
            if (limit > 0) ps.setInt(10, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(new JSONObject()
                            .put("id", rs.getLong("id"))
                            .put("deviceId", rs.getLong("device_id"))
                            .put("name", rs.getString("name") == null ? "" : rs.getString("name"))
                            .put("serialNumber", rs.getString("serial_number") == null ? "" : rs.getString("serial_number"))
                            .put("eventDate", rs.getString("event_date") == null ? "" : rs.getString("event_date"))
                            .put("eventType", rs.getString("event_type") == null ? "" : rs.getString("event_type"))
                            .put("fromPlace", rs.getString("from_place") == null ? "" : rs.getString("from_place"))
                            .put("toPlace", rs.getString("to_place") == null ? "" : rs.getString("to_place"))
                            .put("note", rs.getString("note") == null ? "" : rs.getString("note"))
                            .put("createdBy", rs.getString("created_by") == null ? "" : rs.getString("created_by"))
                            .put("createdAt", rs.getString("created_at") == null ? "" : rs.getString("created_at")));
                }
            }
        }
        return result;
    }

    public static JSONArray getDeviceMovementHistory(long deviceId) throws SQLException {
        JSONArray result = new JSONArray();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT id,event_date,event_type,from_place,to_place,note,created_by,created_at FROM device_movements WHERE device_id=? ORDER BY event_date DESC,id DESC")) {
            ps.setLong(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(new JSONObject().put("id",rs.getLong(1)).put("eventDate",rs.getString(2))
                        .put("eventType",rs.getString(3)).put("fromPlace",rs.getString(4)==null?"":rs.getString(4))
                        .put("toPlace",rs.getString(5)==null?"":rs.getString(5)).put("note",rs.getString(6)==null?"":rs.getString(6))
                        .put("createdBy",rs.getString(7)==null?"":rs.getString(7)).put("createdAt",rs.getString(8)));
            }
        }
        return result;
    }

    public static JSONArray getDeviceDocumentHistory(long deviceId) throws SQLException {
        JSONArray result = new JSONArray();
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("SELECT id,document_id,document_title,document_number,event_type,event_date,created_by FROM device_document_history WHERE device_id=? ORDER BY id DESC")) {
            ps.setLong(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(new JSONObject().put("id",rs.getLong(1)).put("documentId",rs.getObject(2)==null?JSONObject.NULL:rs.getLong(2))
                        .put("documentTitle",rs.getString(3)).put("documentNumber",rs.getString(4)==null?"":rs.getString(4))
                        .put("eventType",rs.getString(5)).put("eventDate",rs.getString(6)).put("createdBy",rs.getString(7)==null?"":rs.getString(7)));
            }
        }
        return result;
    }

    public static void addDeviceMovement(long deviceId, String eventDate, String eventType, String fromPlace, String toPlace, String note, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO device_movements(device_id,event_date,event_type,from_place,to_place,note,created_by,created_at) SELECT ?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM devices WHERE id=? AND is_deleted=0)")) {
                    ps.setLong(1,deviceId);
                    setDateOrNull(ps, 2, eventDate);
                    ps.setString(3,eventType);
                    ps.setString(4,fromPlace);
                    ps.setString(5,toPlace);
                    ps.setString(6,note);
                    ps.setString(7,user);
                    ps.setString(8,LocalDateTime.now().format(DB_DATE_FORMAT));
                    ps.setLong(9,deviceId);
                    if (ps.executeUpdate()==0) throw new SQLException("Прибор не найден");
                }
                // Для операций установки обновляем текущую позицию прибора.
                if ("Установлен".equals(eventType) && toPlace != null && !toPlace.isBlank()) {
                    try (PreparedStatement ps=c.prepareStatement("UPDATE devices SET location=?,version=version+1 WHERE id=?")) { ps.setString(1,toPlace); ps.setLong(2,deviceId); ps.executeUpdate(); }
                }
                log(c,deviceId,user,"MOVEMENT",eventType + (fromPlace==null||fromPlace.isBlank()?"":"; откуда: "+fromPlace) + (toPlace==null||toPlace.isBlank()?"":"; куда: "+toPlace) + (note==null||note.isBlank()?"":"; "+note));
                c.commit();
            } catch(SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
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

    private static void appendChangedJsonFields(StringBuilder sb, String oldJson, String newJson) {
        JSONObject oldObject;
        JSONObject newObject;
        try { oldObject = new JSONObject(oldJson == null || oldJson.isBlank() ? "{}" : oldJson); }
        catch (Exception ignored) { oldObject = new JSONObject(); }
        try { newObject = new JSONObject(newJson == null || newJson.isBlank() ? "{}" : newJson); }
        catch (Exception ignored) { newObject = new JSONObject(); }

        Set<String> keys = new LinkedHashSet<>();
        oldObject.keySet().forEach(keys::add);
        newObject.keySet().forEach(keys::add);
        for (String key : keys) {
            Object oldValue = oldObject.has(key) ? oldObject.opt(key) : JSONObject.NULL;
            Object newValue = newObject.has(key) ? newObject.opt(key) : JSONObject.NULL;
            String oldText = auditValue(oldValue);
            String newText = auditValue(newValue);
            if (!oldText.equals(newText)) {
                appendIfChanged(sb, deviceFieldLabel(key), oldText, newText);
            }
        }
    }

    private static String auditValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONObject || value instanceof JSONArray) return value.toString();
        return String.valueOf(value);
    }

    private static String deviceFieldLabel(String key) {
        switch (key) {
            case "sensorType": return "Тип датчика";
            case "unit": return "Единица измерения";
            case "scaleMin": return "Минимальная шкала";
            case "scaleMax": return "Максимальная шкала";
            case "category": return "Категория";
            case "mpi": return "МПИ";
            case "accuracyClass": return "Класс точности";
            case "redLine": return "Красная черта";
            case "writeOff": return "Списание";
            case "deferredVerification": return "Отсрочка поверки";
            case "movement": return "Движение";
            case "rowNumber": return "Номер строки";
            case "groupName": return "Группа";
            case "positionName": return "Позиционное обозначение";
            default: return key;
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

