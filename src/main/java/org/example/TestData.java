package org.example;

import java.sql.*;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class TestData {

    private static final String[] NAMES = {
            "Манометр МП-100", "Манометр МП-160", "Манометр ЭКМ-1У",
            "Термометр ТК-5", "Термометр ТСМ-50", "Термометр ТСП-100",
            "Расходомер РМ-5", "Расходомер ЭРСВ-500", "Счётчик газа СГ-16",
            "Датчик давления ДД-10", "Датчик давления ДМ-500",
            "Преобразователь ПГ-10", "Преобразователь П-200",
            "Уровнемер УМ-2", "Уровнемер РУ-100",
            "Виброметр ВМ-1", "Газоанализатор ГА-3",
            "Мановакуумметр МВ-1", "Тягонапоромер ТН-2",
            "Напоромер Н-4"
    };

    private static final String[] LOCATIONS = {
            "Цех №1, участок А", "Цех №1, участок Б", "Цех №2, участок А",
            "Цех №2, участок В", "Цех №3", "Цех №4, склад",
            "Насосная станция", "Котельная", "Компрессорная",
            "Лаборатория", "Склад ГСМ", "Участок розлива",
            "Участок упаковки", "Участок подготовки", "Галерея конвейера"
    };

    // Убираем фиксированный seed (42), чтобы номера были разными при каждом запуске
    private static final Random RND = new Random();

    public static void seedIfEmpty() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:kip.db")) {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM devices")) {
                rs.next();
                long count = rs.getLong(1);
                if (count > 0) {
                    System.out.println("Тестовые данные не нужны: в devices уже " + count + " записей");
                    return;
                }
            }

            System.out.println("Заполняю devices тестовыми записями...");
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO devices(serial_number, name, location, last_verification, next_verification, photo_path) " +
                            "VALUES (?, ?, ?, ?, ?, NULL)")) {

                // Множество для контроля уникальности номеров внутри пакета
                Set<String> usedSerials = new HashSet<>();

                for (int i = 0; i < 3300; i++) {
                    // Генерируем случайный 6-значный номер (100000 - 999999)
                    String serial;
                    do {
                        serial = String.format("%06d", 100000 + RND.nextInt(900000));
                    } while (usedSerials.contains(serial));
                    usedSerials.add(serial);

                    ps.setString(1, serial);
                    ps.setString(2, NAMES[RND.nextInt(NAMES.length)] + " №" + (1000 + i));
                    ps.setString(3, LOCATIONS[RND.nextInt(LOCATIONS.length)]);
                    LocalDate last = LocalDate.now().minusDays(1 + RND.nextInt(400));
                    LocalDate next = last.plusDays(365 + (RND.nextInt(61) - 30));
                    ps.setString(4, last.toString());
                    ps.setString(5, next.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            System.out.println("Готово: 12 записей добавлено.");
        }
    }
}