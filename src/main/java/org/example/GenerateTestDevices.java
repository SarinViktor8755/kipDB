
package org.example;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public class GenerateTestDevices {

    private static final int DEVICES_COUNT = 3000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String[] DEVICE_NAMES = {
            "Манометр МП-100",
            "Манометр МП-160",
            "Манометр ЭКМ-1У",
            "Манометр МП-4У",
            "Термометр ТК-5",
            "Термометр ТСМ-50",
            "Термометр ТСП-100",
            "Термометр ТП-2",
            "Расходомер РМ-5",
            "Расходомер ЭРСВ-500",
            "Расходомер РСМ-05",
            "Счетчик газа СГ-16",
            "Счетчик воды ВСКМ-20",
            "Датчик давления ДД-10",
            "Датчик давления ДМ-500",
            "Датчик давления Метран-100",
            "Преобразователь ПГ-10",
            "Преобразователь П-200",
            "Преобразователь Сапфир-22",
            "Уровнемер УМ-2",
            "Уровнемер РУ-100",
            "Уровнемер ЭХО-3",
            "Виброметр ВМ-1",
            "Газоанализатор ГА-3",
            "Газоанализатор СТМ-10",
            "Мановакуумметр МВ-1",
            "Тягонапоромер ТН-2",
            "Напоромер Н-4",
            "Вакуумметр ВП-3",
            "Реле давления РД-2",
            "Реле температуры РТ-1",
            "Датчик температуры ДТ-100",
            "Термопара ТХА-1",
            "Термопреобразователь ТСМ-1",
            "Датчик уровня ДУ-10",
            "Датчик расхода ДР-5",
            "Преобразователь частоты ПЧ-1",
            "Регулятор температуры РТ-100",
            "Сигнализатор уровня СУ-1",
            "Индикатор давления ИД-5"
    };

    private static final String[] LOCATIONS = {
            "Цех №1, участок А",
            "Цех №1, участок Б",
            "Цех №2, участок А",
            "Цех №2, участок В",
            "Цех №3",
            "Цех №4, склад",
            "Насосная станция",
            "Котельная",
            "Компрессорная",
            "Лаборатория",
            "Склад ГСМ",
            "Участок розлива",
            "Участок упаковки",
            "Участок подготовки",
            "Галерея конвейера",
            "Щитовая",
            "Помещение КИПиА",
            "Главный корпус",
            "Тепловой пункт",
            "Водоподготовка"
    };

    private static final DateTimeFormatter DB_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {

        System.out.println("====================================");
        System.out.println(" Генерация тестовых приборов");
        System.out.println("====================================");

        try {
            // Инициализация БД и проверка структуры таблиц
            Db.init();

            System.out.println("Используемая БД: " + Db.getDatabasePath());

            try (Connection connection = Db.openConnection()) {

                connection.setAutoCommit(false);

                try {
                    // Существующие номера приборов
                    Set<String> usedNumbers = new HashSet<>();

                    try (Statement statement = connection.createStatement();
                         ResultSet rs = statement.executeQuery(
                                 "SELECT serial_number FROM devices")) {

                        while (rs.next()) {
                            String number = rs.getString("serial_number");

                            if (number != null && !number.isBlank()) {
                                usedNumbers.add(number);
                            }
                        }
                    }

                    System.out.println(
                            "Уже занято номеров: " + usedNumbers.size()
                    );

                    String sql = """
                        INSERT INTO devices (
                            serial_number,
                            name,
                            location,
                            last_verification,
                            next_verification,
                            photo_path,
                            photo_thumb_path,
                            is_deleted,
                            version,
                            created_at
                        )
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, 0, 1, ?)
                        """;

                    LocalDate today = LocalDate.now();

                    try (PreparedStatement ps =
                                 connection.prepareStatement(sql)) {

                        for (int i = 1; i <= DEVICES_COUNT; i++) {

                            // Генерация уникального шестизначного номера
                            String serialNumber;

                            do {
                                serialNumber = String.valueOf(
                                        100000 + RANDOM.nextInt(900000)
                                );
                            } while (!usedNumbers.add(serialNumber));

                            // Уникальное название прибора
                            String baseName = DEVICE_NAMES[
                                    RANDOM.nextInt(DEVICE_NAMES.length)
                                    ];

                            String deviceName = baseName
                                    + " (тестовый прибор "
                                    + String.format("%04d", i)
                                    + ")";

                            // Место установки
                            String location = LOCATIONS[
                                    RANDOM.nextInt(LOCATIONS.length)
                                    ];

                            // Даты поверки
                            LocalDate lastVerification =
                                    today.minusDays(
                                            1 + RANDOM.nextInt(700)
                                    );

                            LocalDate nextVerification =
                                    lastVerification.plusDays(
                                            365 + RANDOM.nextInt(61)
                                    );

                            String createdAt = LocalDateTime.now()
                                    .format(DB_DATE_FORMAT);

                            ps.setString(1, serialNumber);
                            ps.setString(2, deviceName);
                            ps.setString(3, location);
                            ps.setString(4, lastVerification.toString());
                            ps.setString(5, nextVerification.toString());
                            ps.setString(6, createdAt);

                            ps.addBatch();

                            // Выполняем пакетами по 500 записей
                            if (i % 500 == 0) {
                                ps.executeBatch();
                                System.out.println(
                                        "Добавлено: " + i + " / "
                                                + DEVICES_COUNT
                                );
                            }
                        }

                        ps.executeBatch();
                    }

                    connection.commit();

                    System.out.println();
                    System.out.println("====================================");
                    System.out.println(" ГОТОВО!");
                    System.out.println(" Добавлено приборов: " + DEVICES_COUNT);
                    System.out.println("====================================");

                } catch (Exception e) {

                    connection.rollback();

                    System.err.println(
                            "Ошибка! Изменения отменены."
                    );

                    throw e;

                } finally {
                    connection.setAutoCommit(true);
                }
            }

        } catch (Exception e) {
            System.err.println("Ошибка генерации тестовых данных:");
            e.printStackTrace();
        }
    }
}