package org.example;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id INTEGER,
                    changed_at TEXT,
                    changed_by TEXT,
                    action TEXT,
                    details TEXT
                );
                """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    login TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'operator'
                );
                """);
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
                             "d.name, d.serial_number " +
                             "FROM audit_log a " +
                             "LEFT JOIN devices d ON a.device_id = d.id " +
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