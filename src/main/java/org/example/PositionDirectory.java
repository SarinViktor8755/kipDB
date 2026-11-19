package org.example;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Справочник позиций хранится в той же SQLite-базе, что и приборы. */
public final class PositionDirectory {
    private PositionDirectory() {}

    /** Создаёт таблицу в основной kip.db и заполняет её из исходного справочника только при пустой таблице. */
    public static void init() throws Exception {
        try (Connection c = Db.openConnection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS book_positions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "position TEXT NOT NULL COLLATE NOCASE UNIQUE," +
                    "description TEXT NOT NULL DEFAULT ''," +
                    "equipment TEXT NOT NULL DEFAULT ''," +
                    "source_page INTEGER," +
                    "updated_at TEXT NOT NULL DEFAULT (datetime('now')))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_book_positions_position ON book_positions(position COLLATE NOCASE)");
            // Нормализация распознанного из PDF сокращения: «О В П» -> «ОВП».
            s.execute("UPDATE book_positions SET equipment='ОВП', updated_at=datetime('now') " +
                    "WHERE upper(replace(replace(replace(trim(equipment),' ',''), char(160),''), char(9),''))='ОВП'");
        }
        seedFromBook1IfEmpty();
        linkExistingDevices();
    }

    /** Однократно связывает старые карточки с ID справочника по коду позиции или паре оборудование/наименование. */
    private static void linkExistingDevices() throws SQLException {
        String sql = "UPDATE devices SET details_json=json_set(COALESCE(details_json,'{}'),'$.referencePositionId',(" +
                "SELECT b.id FROM book_positions b WHERE b.position=devices.location COLLATE NOCASE " +
                "OR (b.description=json_extract(devices.details_json,'$.positionName') COLLATE NOCASE " +
                "AND b.equipment=json_extract(devices.details_json,'$.groupName') COLLATE NOCASE) LIMIT 1)) " +
                "WHERE COALESCE(is_deleted,0)=0 AND json_extract(COALESCE(details_json,'{}'),'$.referencePositionId') IS NULL " +
                "AND EXISTS(SELECT 1 FROM book_positions b WHERE b.position=devices.location COLLATE NOCASE " +
                "OR (b.description=json_extract(devices.details_json,'$.positionName') COLLATE NOCASE " +
                "AND b.equipment=json_extract(devices.details_json,'$.groupName') COLLATE NOCASE))";
        try (Connection c=Db.openConnection(); Statement st=c.createStatement()) { st.executeUpdate(sql); }
    }
    private static void seedFromBook1IfEmpty() throws Exception {
        try (Connection c = Db.openConnection()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM book_positions")) {
                if (rs.next() && rs.getLong(1) > 0) return;
            }
            try (InputStream in = PositionDirectory.class.getResourceAsStream("/positions-book1.json")) {
                if (in == null) throw new IllegalStateException("Не найден ресурс positions-book1.json");
                JSONArray data = new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO book_positions(position,description,equipment,source_page) VALUES(?,?,?,?)")) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject x = data.getJSONObject(i);
                        String position = clean(x.optString("position", ""));
                        if (position.isEmpty()) continue;
                        ps.setString(1, position);
                        ps.setString(2, clean(x.optString("description", "")));
                        ps.setString(3, clean(x.optString("equipment", "")));
                        ps.setInt(4, x.optInt("sourcePage", 0));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
    }

    public static JSONArray list() throws SQLException {
        JSONArray a = new JSONArray();
        String sql = "SELECT id,position,description,equipment,source_page,updated_at FROM book_positions ORDER BY position COLLATE NOCASE";
        try (Connection c = Db.openConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) a.put(json(r));
        }
        return a;
    }

    public static JSONObject update(long id, String position, String description, String equipment) throws SQLException {
        position = clean(position);
        description = clean(description);
        equipment = clean(equipment);
        if (position.isBlank()) throw new IllegalArgumentException("Поле «Позиция» обязательно");
        try (Connection c = Db.openConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("UPDATE book_positions SET position=?,description=?,equipment=?,updated_at=datetime('now') WHERE id=?")) {
                    p.setString(1, position); p.setString(2, description); p.setString(3, equipment); p.setLong(4, id);
                    if (p.executeUpdate() == 0) { c.rollback(); return null; }
                }
                // Сохраняем связь по ID справочника и синхронизируем все карточки, которые на него ссылаются.
                JSONArray linkedDevices = new JSONArray();
                try (PreparedStatement q = c.prepareStatement("SELECT d.id,d.details_json,d.location,w.status FROM devices d LEFT JOIN device_workflow w ON w.device_id=d.id WHERE json_extract(d.details_json,'$.referencePositionId')=? AND COALESCE(d.is_deleted,0)=0")) {
                    q.setLong(1, id);
                    try (ResultSet rs = q.executeQuery()) {
                        while (rs.next()) linkedDevices.put(new JSONObject()
                                .put("id", rs.getLong("id"))
                                .put("details", rs.getString("details_json") == null ? "{}" : rs.getString("details_json"))
                                .put("location", rs.getString("location") == null ? "" : rs.getString("location"))
                                .put("status", rs.getString("status") == null ? "" : rs.getString("status")));
                    }
                }
                for (int i = 0; i < linkedDevices.length(); i++) {
                    JSONObject linked = linkedDevices.getJSONObject(i), details;
                    try { details = new JSONObject(linked.optString("details", "{}")); } catch (Exception ignored) { details = new JSONObject(); }
                    details.put("groupName", equipment).put("positionName", description);
                    String location = "INSTALLED".equals(linked.optString("status")) ? position : linked.optString("location", "");
                    try (PreparedStatement u = c.prepareStatement("UPDATE devices SET name=?,location=?,details_json=?,version=version+1 WHERE id=?")) {
                        u.setString(1, description.isBlank() ? "Без позиции" : description);
                        u.setString(2, location);
                        u.setString(3, details.toString());
                        u.setLong(4, linked.getLong("id"));
                        u.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); if (e instanceof SQLException se) throw se; throw new SQLException(e); }
        }
        try (Connection c = Db.openConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT id,position,description,equipment,source_page,updated_at FROM book_positions WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? json(r) : null; }
        }
    }

    public static boolean delete(long id) throws SQLException {
        try (Connection c = Db.openConnection();
             PreparedStatement p = c.prepareStatement("DELETE FROM book_positions WHERE id=?")) {
            p.setLong(1, id);
            return p.executeUpdate() > 0;
        }
    }

    private static JSONObject json(ResultSet r) throws SQLException {
        return new JSONObject().put("id", r.getLong("id")).put("position", r.getString("position"))
                .put("description", r.getString("description")).put("equipment", r.getString("equipment"))
                .put("sourcePage", r.getInt("source_page")).put("updatedAt", r.getString("updated_at"));
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
