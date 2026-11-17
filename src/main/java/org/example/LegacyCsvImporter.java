package org.example;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Импортирует старую CSV-базу «1 БД и движение СИ» в текущую SQLite-БД.
 *
 * CSV: разделитель ';', кодировка Windows-1251, первая строка — заголовки.
 * Переносит приборы в devices, историю из столбца «Движение» —
 * в device_movements. Дополнительные поля старой таблицы сохраняются в details_json.
 *
 * Запуск из папки, где находится CSV (имя файла по умолчанию):
 *   java org.example.LegacyCsvImporter
 *
 * Или с указанием другого CSV-файла:
 *   java org.example.LegacyCsvImporter "C:\\путь\\к\\файлу.csv"
 *
 * Можно указать путь к БД отдельно:
 *   java -Dkip.db.path="C:\\kipBD\\kip.db" org.example.LegacyCsvImporter "C:\\...\\file.csv"
 *
 * Повторный запуск не создаёт повторные приборы: проверка по serial_number.
 * Если прибор уже есть в новой БД, он целиком пропускается вместе с историей.
 */
public final class LegacyCsvImporter {

    private static final Charset CSV_CHARSET = Charset.forName("windows-1251");
    private static final DateTimeFormatter DB_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final Pattern MOVEMENT_DATE =
            Pattern.compile("^\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*:\\s*(.*?)\\s*$");
    private static final String IMPORT_USER = "Импорт из старой БД";

    private LegacyCsvImporter() {}

    // ---------------------------------------------------------------------
    // Модель строки CSV
    // ---------------------------------------------------------------------

    private static final class RawDevice {
        String serialNumber;
        String position;
        String sensorType;
        String unit;
        String scaleMin;
        String scaleMax;
        String scale;
        String category;
        String lastVerification;
        String mpi;
        String nextVerification;
        String accuracyClass;
        String redLine;
        String writeOff;
        String deferredVerification;
        String movement;
        String rowNumber;
        String groupName;
        String positionName;
        String legacyNote;

        RawDevice(Map<String, String> row) {
            serialNumber = value(row, "Заводской номер");
            position = value(row, "Позиция");
            sensorType = value(row, "Тип датчика");
            unit = value(row, "ЕИ");
            scaleMin = value(row, "Минимум шкалы");
            scaleMax = value(row, "Максимум шкалы");
            scale = value(row, "Шкала");
            category = value(row, "П/К");
            lastVerification = value(row, "Дата поверки");
            mpi = value(row, "МПИ");
            nextVerification = value(row, "Дата следующей поверки");
            accuracyClass = value(row, "Класс точности");
            redLine = value(row, "Красная черта");
            writeOff = value(row, "Списание");
            deferredVerification = value(row, "Отложенная поверка");
            movement = value(row, "Движение");
            rowNumber = value(row, "Строка");
            groupName = value(row, "Группа");
            positionName = value(row, "Наименование позиции");
            legacyNote = value(row, "Примечание старой БД");
        }
    }

    private static String value(Map<String, String> row, String key) {
        String v = row.get(key);
        return v == null ? "" : v.trim();
    }

    private static final class MovementEvent {
        final String date;
        final String type;
        final String from;
        final String to;
        final String raw;

        MovementEvent(String date, String type, String from, String to, String raw) {
            this.date = date;
            this.type = type;
            this.from = from;
            this.to = to;
            this.raw = raw;
        }
    }

    private static final class ImportResult {
        int csvRows;
        int devicesInserted;
        int duplicateRows;
        int movementsInserted;
        int movementPartsSkipped;
    }

    // ---------------------------------------------------------------------
    // Чтение CSV. Парсер поддерживает кавычки и переносы строк внутри ячеек.
    // ---------------------------------------------------------------------

    private static List<List<String>> readCsv(Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, CSV_CHARSET)) {
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            int ch;
            while ((ch = reader.read()) != -1) {
                char c = (char) ch;
                if (quoted) {
                    if (c == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') field.append('"');
                        else {
                            quoted = false;
                            if (next != -1) reader.reset();
                        }
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ';') {
                    row.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next != '\n' && next != -1) reader.reset();
                    }
                    row.add(field.toString());
                    field.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
            if (field.length() > 0 || !row.isEmpty()) {
                row.add(field.toString());
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<RawDevice> parseCsv(Path file) throws IOException {
        List<List<String>> rows = readCsv(file);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV-файл пустой");

        List<String> headers = rows.get(0);
        Map<Integer, String> headerByIndex = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = normalizeHeader(headers.get(i));
            if (h.isEmpty() && i == 19) h = "Примечание старой БД";
            headerByIndex.put(i, h);
        }

        List<RawDevice> devices = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            if (cells.stream().allMatch(s -> s == null || s.isBlank())) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> h : headerByIndex.entrySet()) {
                int index = h.getKey();
                if (!h.getValue().isEmpty() && index < cells.size()) {
                    values.put(h.getValue(), cells.get(index));
                }
            }
            RawDevice d = new RawDevice(values);
            if (!d.serialNumber.isBlank()) devices.add(d);
        }
        return devices;
    }

    private static String normalizeHeader(String header) {
        if (header == null) return "";
        return header.replace('\uFEFF', ' ').replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------------
    // Импорт в текущую БД
    // ---------------------------------------------------------------------

    private static ImportResult importIntoDatabase(List<RawDevice> devices) throws SQLException {
        ImportResult result = new ImportResult();
        result.csvRows = devices.size();

        try (Connection c = Db.openConnection()) {
            c.setAutoCommit(false);
            try {
                Set<String> existing = loadExistingSerialNumbers(c);
                System.out.println("Уже в новой БД приборов: " + existing.size());

                String insertDevice = """
                    INSERT INTO devices(
                        serial_number, name, location, last_verification, next_verification,
                        photo_path, photo_thumb_path, is_deleted, version, created_at, details_json
                    ) VALUES (?, ?, ?, ?, ?, NULL, NULL, 0, 1, ?, ?)
                    """;

                String insertMovement = """
                    INSERT INTO device_movements(
                        device_id, event_date, event_type, from_place, to_place, note, created_by, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                try (PreparedStatement devicePs = c.prepareStatement(insertDevice, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement movementPs = c.prepareStatement(insertMovement)) {

                    for (RawDevice d : devices) {
                        String serial = d.serialNumber.trim();
                        if (existing.contains(serial)) {
                            result.duplicateRows++;
                            continue;
                        }

                        String createdAt = LocalDateTime.now().format(DB_TIMESTAMP);
                        String deviceName = firstNonBlank(d.positionName, d.position, d.sensorType, "СИ " + serial);
                        String detailsJson = buildDetailsJson(d).toString();

                        devicePs.setString(1, serial);
                        devicePs.setString(2, deviceName);
                        setStringOrNull(devicePs, 3, d.position);
                        setDateOrNull(devicePs, 4, d.lastVerification);
                        setDateOrNull(devicePs, 5, d.nextVerification);
                        devicePs.setString(6, createdAt);
                        devicePs.setString(7, detailsJson);
                        devicePs.executeUpdate();

                        long deviceId;
                        try (ResultSet keys = devicePs.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("SQLite не вернула ID нового прибора " + serial);
                            deviceId = keys.getLong(1);
                        }
                        existing.add(serial);
                        result.devicesInserted++;

                        for (MovementEvent event : parseMovement(d.movement)) {
                            movementPs.setLong(1, deviceId);
                            movementPs.setString(2, event.date);
                            movementPs.setString(3, event.type);
                            setStringOrNull(movementPs, 4, event.from);
                            setStringOrNull(movementPs, 5, event.to);
                            movementPs.setString(6, event.raw);
                            movementPs.setString(7, IMPORT_USER);
                            movementPs.setString(8, createdAt);
                            movementPs.executeUpdate();
                            result.movementsInserted++;
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException sql) throw sql;
                throw new SQLException("Ошибка импорта CSV", e);
            } finally {
                c.setAutoCommit(true);
            }
        }
        return result;
    }

    private static Set<String> loadExistingSerialNumbers(Connection c) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT serial_number FROM devices WHERE serial_number IS NOT NULL")) {
            while (rs.next()) {
                String serial = rs.getString(1);
                if (serial != null && !serial.isBlank()) result.add(serial.trim());
            }
        }
        return result;
    }

    private static JSONObject buildDetailsJson(RawDevice d) {
        JSONObject details = new JSONObject();
        put(details, "sensorType", d.sensorType);
        put(details, "unit", d.unit);
        put(details, "scaleMin", d.scaleMin);
        put(details, "scaleMax", d.scaleMax);
        put(details, "scale", d.scale);
        put(details, "category", d.category);
        put(details, "mpi", d.mpi);
        put(details, "accuracyClass", d.accuracyClass);
        put(details, "redLine", d.redLine);
        put(details, "writeOff", normalizeDate(d.writeOff));
        put(details, "deferredVerification", normalizeDate(d.deferredVerification));
        put(details, "movement", d.movement);
        put(details, "rowNumber", d.rowNumber);
        put(details, "groupName", d.groupName);
        put(details, "positionName", d.positionName);
        put(details, "legacyNote", d.legacyNote);
        return details;
    }

    private static void put(JSONObject json, String key, String value) {
        if (value != null && !value.isBlank()) json.put(key, value.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "Без наименования";
    }

    private static void setStringOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value.trim());
    }

    private static void setDateOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        String date = normalizeDate(value);
        if (date == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, date);
    }

    private static String normalizeDate(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            if (v.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(v).toString();
            return LocalDate.parse(v, CSV_DATE).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Разбор истории движения из ячейки CSV
    // ---------------------------------------------------------------------

    public static List<MovementEvent> parseMovement(String movement) {
        List<MovementEvent> events = new ArrayList<>();
        if (movement == null || movement.isBlank()) return events;

        for (String fragment : movement.split("\\s*;\\s*")) {
            String raw = fragment.trim();
            if (raw.isEmpty()) continue;

            Matcher dateMatcher = MOVEMENT_DATE.matcher(raw);
            String date = LocalDate.now().toString();
            String description = raw;
            if (dateMatcher.matches()) {
                String normalized = normalizeDate(dateMatcher.group(1));
                if (normalized == null) continue;
                date = normalized;
                description = dateMatcher.group(2).trim();
            }

            String lower = description.toLowerCase(Locale.ROOT);
            String type;
            String from = "";
            String to = "";

            Matcher removed = Pattern.compile("Снят с\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(description);
            Matcher installed = Pattern.compile("Установлен на\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(description);

            if (removed.find()) {
                type = "Снят с позиции";
                from = removed.group(1).trim();
            } else if (installed.find()) {
                type = "Установлен";
                to = installed.group(1).trim();
            } else if (lower.contains("списан") || lower.contains("списание")) {
                type = "Списан";
            } else if (lower.contains("после поверки")) {
                type = "Возвращён после поверки";
                from = "Поверка";
            } else if (lower.contains("поверк")) {
                type = "Передан на поверку";
                to = "Поверка";
            } else if (lower.contains("после калибровки")) {
                type = "Возвращён после калибровки";
                from = "Калибровка";
            } else if (lower.contains("калибров")) {
                type = "Передан на калибровку";
                to = "Калибровка";
            } else if (lower.contains("после ремонта") || lower.contains("из ремонта")) {
                type = "Возвращён из ремонта";
                from = "Ремонт";
            } else if (lower.contains("ремонт")) {
                type = "Передан в ремонт";
                to = "Ремонт";
            } else if (lower.contains("склад")) {
                type = "Передан на склад";
                to = "Склад";
            } else if (lower.contains("резерв")) {
                type = "Передан в резерв";
                to = "Резерв";
            } else {
                type = "Другое";
            }
            events.add(new MovementEvent(date, type, from, to, raw));
        }
        return events;
    }

    // ---------------------------------------------------------------------
    // main
    // ---------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("================================================");
        System.out.println(" Импорт старой CSV-БД СИ в текущую SQLite-БД");
        System.out.println("================================================");

        // По умолчанию берём исходный CSV из текущей рабочей папки.
        // Если передан аргумент командной строки, используем указанный путь.
        String csvFileName = "1 БД и движение СИ (version 1).csv";
        Path csv = (args.length > 0 && args[0] != null && !args[0].isBlank())
                ? Path.of(args[0].trim()).toAbsolutePath().normalize()
                : Path.of(csvFileName).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(csv)) throw new IllegalArgumentException("Файл не найден: " + csv);

            Db.init();
            System.out.println("БД: " + Db.getDatabasePath());
            System.out.println("CSV: " + csv);
            System.out.println("Кодировка: Windows-1251; разделитель: ;");
            System.out.println();

            System.out.println("Чтение и разбор CSV...");
            List<RawDevice> devices = parseCsv(csv);
            int withMovement = 0;
            int totalMovementEvents = 0;
            for (RawDevice d : devices) {
                if (!d.movement.isBlank()) {
                    withMovement++;
                    totalMovementEvents += parseMovement(d.movement).size();
                }
            }
            System.out.println("  Строк с приборами: " + devices.size());
            System.out.println("  Приборов с историей движения: " + withMovement);
            System.out.println("  Событий движения найдено: " + totalMovementEvents);
            System.out.println();

            System.out.println("Запись в SQLite...");
            ImportResult result = importIntoDatabase(devices);
            System.out.println();
            System.out.println("================================================");
            System.out.println(" ИМПОРТ ЗАВЕРШЁН");
            System.out.println("  Строк CSV с приборами:       " + result.csvRows);
            System.out.println("  Добавлено приборов:          " + result.devicesInserted);
            System.out.println("  Пропущено дублей:            " + result.duplicateRows);
            System.out.println("  Добавлено событий движения:  " + result.movementsInserted);
            System.out.println("================================================");

            try (Connection c = Db.openConnection(); Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM devices")) {
                    rs.next();
                    System.out.println("Всего приборов в БД: " + rs.getLong(1));
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM device_movements")) {
                    rs.next();
                    System.out.println("Всего перемещений в БД: " + rs.getLong(1));
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка импорта:");
            e.printStackTrace();
        }
    }
}
