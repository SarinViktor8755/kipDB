package org.example;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсит devices-test-data.json и заполняет БД:
 *   • таблицу devices — самими приборами;
 *   • таблицу device_movements — историей перемещений, извлечённой из поля "movement".
 *
 * Запуск: java org.example.TestDataParser
 * Идемпотентно: повторный запуск не создаёт дублей (проверка по serial_number).
 *
 * Все даты (last_verification, next_verification, event_date) записываются
 * в колонки типа DATE. Пустые/невалидные значения записываются как NULL.
 */
public final class TestDataParser {

    /** Формат, в котором Db хранит даты поверки */
    private static final DateTimeFormatter DB_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Формат «22.09.2022» в поле movement */
    private static final Pattern MOVEMENT_DATE =
            Pattern.compile("^(\\d{2})\\.(\\d{2})\\.(\\d{4})\\s*:\\s*(.*)$");
    /** Формат ISO-даты для проверки перед записью в DATE-колонку */
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Имена для автора тестовых записей перемещений. */
    private static final List<String> TEST_AUTHORS = List.of(
            "Александр Иванов", "Дмитрий Петров", "Сергей Смирнов",
            "Андрей Кузнецов", "Михаил Попов", "Алексей Соколов",
            "Николай Волков", "Евгений Морозов", "Павел Новиков",
            "Игорь Фёдоров", "Роман Павлов", "Максим Семёнов"
    );

    // =====================================================================
    //  Структуры данных
    // =====================================================================

    /** Одна «сырая» запись из JSON */
    public static final class RawDevice {
        public final String serialNumber;
        public final String location;
        public final String name;
        public final String lastVerification;
        public final String nextVerification;

        public final String sensorType;
        public final String unit;
        public final String scaleMin;
        public final String scaleMax;
        public final String category;
        public final String mpi;
        public final String accuracyClass;
        public final String redLine;
        public final String writeOff;
        public final String deferredVerification;
        public final String movement;
        public final String rowNumber;
        public final String groupName;
        public final String positionName;

        RawDevice(JSONObject o) {
            serialNumber = o.optString("serialNumber", "");
            location = o.optString("location", "");
            name = o.optString("name", "");
            lastVerification = o.optString("lastVerification", "");
            nextVerification = o.optString("nextVerification", "");

            JSONObject d = o.optJSONObject("details");
            if (d == null) d = new JSONObject();
            sensorType = d.optString("sensorType", "");
            unit = d.optString("unit", "");
            scaleMin = d.optString("scaleMin", "");
            scaleMax = d.optString("scaleMax", "");
            category = d.optString("category", "");
            mpi = d.optString("mpi", "");
            accuracyClass = d.optString("accuracyClass", "");
            redLine = d.optString("redLine", "");
            writeOff = d.optString("writeOff", "");
            deferredVerification = d.optString("deferredVerification", "");
            movement = d.optString("movement", "");
            rowNumber = d.optString("rowNumber", "");
            groupName = d.optString("groupName", "");
            positionName = d.optString("positionName", "");
        }

        @Override
        public String toString() {
            return positionName + " / " + sensorType + " / " + unit + " " + scaleMin + ".." + scaleMax;
        }
    }

    /** Итог парсинга: «сырьё» для генератора и справка */
    public static final class ParsedData {
        public final List<RawDevice> devices = new ArrayList<>();
        public final List<String> positionNames = new ArrayList<>();
        public final List<String> groupNames = new ArrayList<>();
        public final List<String> sensorTypes = new ArrayList<>();
        public final List<String> units = new ArrayList<>();
        public final List<CharacteristicSet> characteristicSets = new ArrayList<>();
    }

    /** Согласованный набор характеристик одного датчика */
    public static final class CharacteristicSet {
        public final String sensorType;
        public final String unit;
        public final String scaleMin;
        public final String scaleMax;
        public final String category;
        public final String mpi;
        public final String accuracyClass;
        public final String redLine;

        CharacteristicSet(RawDevice d) {
            sensorType = d.sensorType;
            unit = d.unit;
            scaleMin = d.scaleMin;
            scaleMax = d.scaleMax;
            category = d.category;
            mpi = d.mpi;
            accuracyClass = d.accuracyClass;
            redLine = d.redLine;
        }
    }

    private TestDataParser() {}

    // =====================================================================
    //  Парсинг
    // =====================================================================

    public static ParsedData parseFromClasspath() throws Exception {
        try (InputStream in = TestDataParser.class.getResourceAsStream("/devices-test-data.json")) {
            if (in == null) throw new IllegalStateException("Не найден /devices-test-data.json в classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(text);
        }
    }

    public static ParsedData parse(String json) {
        JSONArray array = new JSONArray(json);
        ParsedData out = new ParsedData();

        Set<String> positions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> sensors = new LinkedHashSet<>();
        Set<String> units = new LinkedHashSet<>();

        for (int i = 0; i < array.length(); i++) {
            RawDevice d = new RawDevice(array.getJSONObject(i));
            out.devices.add(d);
            if (!d.positionName.isBlank()) positions.add(d.positionName);
            if (!d.groupName.isBlank())    groups.add(d.groupName);
            if (!d.sensorType.isBlank())   sensors.add(d.sensorType);
            if (!d.unit.isBlank())         units.add(d.unit);
            if (!d.sensorType.isBlank())   out.characteristicSets.add(new CharacteristicSet(d));
        }

        out.positionNames.addAll(positions);
        out.groupNames.addAll(groups);
        out.sensorTypes.addAll(sensors);
        out.units.addAll(units);
        return out;
    }

    // =====================================================================
    //  Заполнение БД
    // =====================================================================

    /**
     * Заполняет devices и device_movements данными из JSON.
     * Возвращает статистику: сколько приборов и перемещений добавлено.
     */
    public static ImportResult importIntoDatabase(ParsedData data) throws SQLException {
        ImportResult result = new ImportResult();

        try (Connection c = Db.openConnection()) {
            c.setAutoCommit(false);
            try {
                Set<String> existing = loadExistingSerialNumbers(c);
                System.out.println("  Уже в БД приборов: " + existing.size());

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

                try (PreparedStatement devPs = c.prepareStatement(insertDevice, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement movPs = c.prepareStatement(insertMovement)) {

                    for (RawDevice d : data.devices) {
                        // Идемпотентность: если такой зав. номер уже есть — пропускаем прибор целиком.
                        if (d.serialNumber != null && !d.serialNumber.isBlank()
                                && existing.contains(d.serialNumber)) {
                            continue;
                        }

                        String created = LocalDateTime.now().format(DB_DATE_FORMAT);
                        String detailsJson = buildDetailsJson(d);

                        devPs.setString(1, emptyToNull(d.serialNumber));
                        devPs.setString(2, d.positionName.isBlank() ? d.name : d.positionName);
                        devPs.setString(3, emptyToNull(d.location));
                        // ВАЖНО: даты пишем через setDateOrNull — гарантирует NULL для пустых
                        // и валидный формат YYYY-MM-DD для непустых значений.
                        setDateOrNull(devPs, 4, d.lastVerification);
                        setDateOrNull(devPs, 5, d.nextVerification);
                        devPs.setString(6, created);
                        devPs.setString(7, detailsJson);

                        long id;
                        devPs.executeUpdate();
                        try (ResultSet rs = devPs.getGeneratedKeys()) {
                            rs.next();
                            id = rs.getLong(1);
                        }
                        result.devicesInserted++;

                        if (d.serialNumber != null && !d.serialNumber.isBlank()) {
                            existing.add(d.serialNumber);
                        }

                        // Парсим «movement» в отдельные события
                        if (d.movement != null && !d.movement.isBlank()) {
                            List<MovementEvent> events = parseMovement(d.movement);
                            for (MovementEvent ev : events) {
                                movPs.setLong(1, id);
                                // event_date — тоже DATE-колонка
                                setDateOrNull(movPs, 2, ev.date);
                                movPs.setString(3, ev.type);
                                movPs.setString(4, emptyToNull(ev.from));
                                movPs.setString(5, emptyToNull(ev.to));
                                movPs.setString(6, ev.raw);
                                movPs.setString(7, randomTestAuthor());
                                movPs.setString(8, created);
                                movPs.executeUpdate();
                                result.movementsInserted++;
                            }
                        }
                    }
                }

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        return result;
    }

    private static String randomTestAuthor() {
        return TEST_AUTHORS.get(java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(TEST_AUTHORS.size()));
    }

    public static final class ImportResult {
        public int devicesInserted;
        public int movementsInserted;
        @Override public String toString() {
            return "devices=" + devicesInserted + ", movements=" + movementsInserted;
        }
    }

    private static Set<String> loadExistingSerialNumbers(Connection c) throws SQLException {
        Set<String> used = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT serial_number FROM devices")) {
            while (rs.next()) {
                String n = rs.getString(1);
                if (n != null && !n.isBlank()) used.add(n);
            }
        }
        return used;
    }

    private static String buildDetailsJson(RawDevice d) {
        JSONObject details = new JSONObject();
        putIfNotBlank(details, "sensorType", d.sensorType);
        putIfNotBlank(details, "unit", d.unit);
        putIfNotBlank(details, "scaleMin", d.scaleMin);
        putIfNotBlank(details, "scaleMax", d.scaleMax);
        putIfNotBlank(details, "category", d.category);
        putIfNotBlank(details, "mpi", d.mpi);
        putIfNotBlank(details, "accuracyClass", d.accuracyClass);
        putIfNotBlank(details, "redLine", d.redLine);
        putIfNotBlank(details, "writeOff", d.writeOff);
        putIfNotBlank(details, "deferredVerification", d.deferredVerification);
        putIfNotBlank(details, "movement", d.movement);
        putIfNotBlank(details, "rowNumber", d.rowNumber);
        putIfNotBlank(details, "groupName", d.groupName);
        putIfNotBlank(details, "positionName", d.positionName);
        return details.toString();
    }

    private static void putIfNotBlank(JSONObject o, String k, String v) {
        if (v != null && !v.isBlank()) o.put(k, v);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Записывает значение как DATE-совместимую строку или NULL.
     * NULL ставится, если:
     *   • значение null / пустое / из одних пробелов;
     *   • значение не соответствует формату YYYY-MM-DD.
     * Это защищает DATE-колонку от «мусорных» значений и обеспечивает
     * корректную работу ORDER BY ... IS NULL.
     */
    private static void setDateOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) { ps.setNull(index, Types.VARCHAR); return; }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !ISO_DATE.matcher(trimmed).matches()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, trimmed);
        }
    }

    // =====================================================================
    //  Разбор поля movement в отдельные события
    // =====================================================================

    public static final class MovementEvent {
        public final String date;   // yyyy-MM-dd
        public final String type;   // «Снят с позиции» / «Установлен» / «Передан на поверку» / «Передан в ремонт» / …
        public final String from;   // откуда (для «Снят с …»)
        public final String to;     // куда  (для «Установлен на …»)
        public final String raw;    // исходный фрагмент
        MovementEvent(String date, String type, String from, String to, String raw) {
            this.date = date; this.type = type; this.from = from; this.to = to; this.raw = raw;
        }
    }

    /**
     * Разбирает строку вида:
     *   "28.09.2022: Снят с PIO-4; 28.09.2022: В ремонт; 18.10.2022: В поверку; ..."
     * Возвращает список событий. Устойчив к пустым фрагментам и пробелам.
     * Дата события всегда нормализуется к формату yyyy-MM-dd.
     */
    public static List<MovementEvent> parseMovement(String movement) {
        List<MovementEvent> events = new ArrayList<>();
        if (movement == null) return events;

        for (String part : movement.split("\\s*;\\s*")) {
            String entry = part.trim();
            if (entry.isEmpty()) continue;

            String date = LocalDate.now().toString();
            String description = entry;

            Matcher m = MOVEMENT_DATE.matcher(entry);
            if (m.matches()) {
                date = m.group(3) + "-" + m.group(2) + "-" + m.group(1);
                description = m.group(4).trim();
            }

            String type;
            String from = "";
            String to = "";

            String lower = description.toLowerCase(Locale.ROOT);

            Matcher removed = Pattern.compile("Снят с\\s+([^;]+)", Pattern.CASE_INSENSITIVE).matcher(description);
            Matcher installed = Pattern.compile("Установлен на\\s+([^;]+)", Pattern.CASE_INSENSITIVE).matcher(description);

            if (removed.find()) {
                type = "Снят с позиции"; from = removed.group(1).trim();
            } else if (installed.find()) {
                type = "Установлен"; to = installed.group(1).trim();
            } else if (lower.contains("списан") || lower.contains("спис")) {
                type = "Списан";
            } else if (lower.contains("поверк")) {
                type = lower.contains("после") ? "Возвращён после поверки" : "Передан на поверку";
            } else if (lower.contains("калибров")) {
                type = lower.contains("после") ? "Возвращён после калибровки" : "Передан на калибровку";
            } else if (lower.contains("ремонт")) {
                type = lower.contains("после") ? "Возвращён из ремонта" : "Передан в ремонт";
            } else if (lower.contains("установлен") || lower.contains("установка")) {
                type = "Установлен";
            } else if (lower.contains("склад")) {
                type = "Передан на склад";
            } else if (lower.contains("резерв")) {
                type = "Передан в резерв";
            } else if (lower.contains("списан")) {
                type = "Списан";
            } else {
                type = "Другое";
            }

            events.add(new MovementEvent(date, type, from, to, entry));
        }
        return events;
    }

    // =====================================================================
    //  main
    // =====================================================================

    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println(" Импорт devices-test-data.json в базу данных");
        System.out.println("============================================");

        try {
            Db.init();
            System.out.println("БД: " + Db.getDatabasePath());

            System.out.println();
            System.out.println("Парсинг /devices-test-data.json…");
            ParsedData data = parseFromClasspath();
            System.out.printf("  записей:       %d%n", data.devices.size());
            System.out.printf("  позиций:       %d%n", data.positionNames.size());
            System.out.printf("  групп:         %d%n", data.groupNames.size());
            System.out.printf("  sensorType:    %d%n", data.sensorTypes.size());
            System.out.printf("  наборов хар-к: %d%n", data.characteristicSets.size());

            // Небольшая сводка «сколько у кого истории»
            int withMovement = 0;
            int totalEvents = 0;
            for (RawDevice d : data.devices) {
                if (d.movement != null && !d.movement.isBlank()) {
                    withMovement++;
                    totalEvents += parseMovement(d.movement).size();
                }
            }
            System.out.printf("  с историей:    %d (событий: %d)%n", withMovement, totalEvents);

            System.out.println();
            System.out.println("Импорт в БД…");
            ImportResult r = importIntoDatabase(data);

            System.out.println();
            System.out.println("============================================");
            System.out.println(" ГОТОВО!");
            System.out.printf ("  Добавлено приборов:     %d%n", r.devicesInserted);
            System.out.printf ("  Добавлено перемещений:  %d%n", r.movementsInserted);
            System.out.println("============================================");

            // Проверка: сколько теперь в БД
            try (Connection c = Db.openConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM devices")) {
                rs.next();
                System.out.println("Всего приборов в БД: " + rs.getLong(1));
            }
            try (Connection c = Db.openConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM device_movements")) {
                rs.next();
                System.out.println("Всего перемещений в БД: " + rs.getLong(1));
            }
        } catch (Exception e) {
            System.err.println("Ошибка импорта:");
            e.printStackTrace();
        }
    }
}
