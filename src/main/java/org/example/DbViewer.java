package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DbViewer {

    private static final String DB_URL = "jdbc:sqlite:kip.db";

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("       ПРОСМОТР СТРУКТУРЫ БАЗЫ ДАННЫХ KIP.DB      ");
        System.out.println("==================================================\n");

        try (Connection conn = DriverManager.getConnection(DB_URL)) {

            // Получаем список всех таблиц (исключая системные таблицы SQLite)
            List<String> tables = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) {
                    tables.add(rs.getString("name"));
                }
            }

            if (tables.isEmpty()) {
                System.out.println("База данных пуста или таблицы еще не созданы.");
                return;
            }

            // Проходим по каждой таблице
            for (String table : tables) {
                printTable(conn, table);
            }

        } catch (SQLException e) {
            System.err.println("Ошибка подключения к БД: " + e.getMessage());
            System.err.println("Убедитесь, что файл kip.db находится в текущей директории.");
        }
    }

    private static void printTable(Connection conn, String tableName) {
        System.out.println("--------------------------------------------------");
        System.out.println("ТАБЛИЦА: " + tableName.toUpperCase());
        System.out.println("--------------------------------------------------");

        // 1. Получаем структуру таблицы (колонки)
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {

            System.out.println("Структура (колонки):");
            while (rs.next()) {
                String colName = rs.getString("name");
                String colType = rs.getString("type");
                int pk = rs.getInt("pk");
                System.out.printf("  - %-20s : %-10s %s%n", colName, colType, pk == 1 ? "[PRIMARY KEY]" : "");
            }
            System.out.println();
        } catch (SQLException e) {
            System.out.println("  Ошибка чтения структуры: " + e.getMessage());
            return;
        }

        // 2. Получаем первые 10 строк данных
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + " LIMIT 10")) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            System.out.println("Первые 10 строк:");

            // Формируем заголовок таблицы
            StringBuilder header = new StringBuilder("  ");
            for (int i = 1; i <= columnCount; i++) {
                String colName = metaData.getColumnName(i);
                header.append(String.format("%-15s | ", truncate(colName, 15)));
            }
            System.out.println(header.toString());
            System.out.println("  " + "-".repeat(header.length() - 2));

            // Выводим данные
            int count = 0;
            while (rs.next() && count < 10) {
                StringBuilder row = new StringBuilder("  ");
                for (int i = 1; i <= columnCount; i++) {
                    String value = rs.getString(i);
                    if (value == null) value = "NULL";
                    row.append(String.format("%-15s | ", truncate(value, 15)));
                }
                System.out.println(row.toString());
                count++;
            }

            if (count == 0) {
                System.out.println("  (Таблица пуста)");
            } else {
                // Проверяем, есть ли еще записи
                try (Statement countStmt = conn.createStatement();
                     ResultSet countRs = countStmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                    countRs.next();
                    long total = countRs.getLong(1);
                    if (total > 10) {
                        System.out.println("  ... и еще " + (total - 10) + " строк(и).");
                    } else {
                        System.out.println("  Всего строк: " + total);
                    }
                }
            }

        } catch (SQLException e) {
            System.out.println("  Ошибка чтения данных: " + e.getMessage());
        }

        System.out.println(); // Пустая строка между таблицами
    }

    // Вспомогательный метод для обрезки длинных строк, чтобы таблица не "разъезжалась"
    private static String truncate(String s, int max) {
        if (s == null) return "NULL";
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}