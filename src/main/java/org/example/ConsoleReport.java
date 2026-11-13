package org.example;

import java.sql.*;
import java.time.LocalDate;
import java.util.List;

public class ConsoleReport {

    public static void printSummary() throws SQLException {
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        String in30 = today.plusDays(30).toString();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:kip.db");
             Statement s = c.createStatement()) {
            long total = queryLong(s, "SELECT COUNT(*) FROM devices");
            long overdue = queryLong(s,
                    "SELECT COUNT(*) FROM devices WHERE next_verification < '" + todayStr + "'");
            long soon = queryLong(s,
                    "SELECT COUNT(*) FROM devices WHERE next_verification >= '" + todayStr + "' " +
                            "AND next_verification <= '" + in30 + "'");
            long audit = queryLong(s, "SELECT COUNT(*) FROM audit_log");

            System.out.println();
            System.out.println("========== СВОДКА ==========");
            System.out.printf("Всего приборов:        %d%n", total);
            System.out.printf("Просрочено поверок:    %d%n", overdue);
            System.out.printf("Истекают в 30 дней:    %d%n", soon);
            System.out.printf("Записей в audit_log:   %d%n", audit);
            System.out.println("============================");
        }
    }

    public static void printDevices(int limit) throws SQLException {
        System.out.println();
        System.out.println("=== Первые " + limit + " приборов (devices) ===");
        System.out.printf("%-5s %-28s %-26s %-12s %-12s%n",
                "ID", "Наименование", "Расположение", "Последняя", "Следующая");
        System.out.println("-".repeat(90));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:kip.db");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, location, last_verification, next_verification " +
                             "FROM devices ORDER BY id LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf("%-5d %-28s %-26s %-12s %-12s%n",
                            rs.getLong("id"),
                            truncate(rs.getString("name"), 28),
                            truncate(rs.getString("location"), 26),
                            nvl(rs.getString("last_verification")),
                            nvl(rs.getString("next_verification")));
                }
            }
        }
        System.out.println("-".repeat(90));
    }

    public static void printAllDevices() throws SQLException {
        System.out.println();
        System.out.println("=== ВСЕ приборы (devices) ===");
        System.out.printf("%-5s %-28s %-26s %-12s %-12s %-8s%n",
                "ID", "Наименование", "Расположение", "Последняя", "Следующая", "Статус");
        System.out.println("-".repeat(100));

        String today = LocalDate.now().toString();
        String in30 = LocalDate.now().plusDays(30).toString();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:kip.db");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, name, location, last_verification, next_verification " +
                             "FROM devices ORDER BY id")) {

            int n = 0;
            while (rs.next()) {
                n++;
                String next = rs.getString("next_verification");
                System.out.printf("%-5d %-28s %-26s %-12s %-12s %-8s%n",
                        rs.getLong("id"),
                        truncate(rs.getString("name"), 28),
                        truncate(rs.getString("location"), 26),
                        nvl(rs.getString("last_verification")),
                        nvl(next),
                        statusOf(next, today, in30));
            }
            System.out.println("-".repeat(100));
            System.out.println("Всего строк: " + n);
        }
    }

    public static void printAudit(int limit) throws SQLException {
        System.out.println();
        System.out.println("=== Последние " + limit + " записей audit_log ===");
        System.out.printf("%-20s %-10s %-8s %-40s%n", "Когда", "Кто", "Действие", "Детали");
        System.out.println("-".repeat(90));
        List<String[]> rows = Db.getAudit(limit);
        for (String[] r : rows) {
            System.out.printf("%-20s %-10s %-8s %-40s%n",
                    truncate(r[0], 20), truncate(r[1], 10),
                    truncate(r[2], 8), truncate(r[3], 40));
        }
        System.out.println("-".repeat(90));
    }

    public static void printAllAudit() throws SQLException {
        System.out.println();
        System.out.println("=== ВЕСЬ журнал audit_log ===");
        System.out.printf("%-20s %-10s %-8s %-40s%n", "Когда", "Кто", "Действие", "Детали");
        System.out.println("-".repeat(90));
        List<String[]> rows = Db.getAudit(Integer.MAX_VALUE);
        for (String[] r : rows) {
            System.out.printf("%-20s %-10s %-8s %-40s%n",
                    truncate(r[0], 20), truncate(r[1], 10),
                    truncate(r[2], 8), truncate(r[3], 40));
        }
        System.out.println("-".repeat(90));
        System.out.println("Всего строк: " + rows.size());
    }

    public static void printAll() throws SQLException {
        printSummary();
        printAllDevices();
        printAllAudit();
    }

    private static String statusOf(String nextDate, String today, String in30) {
        if (nextDate == null) return "?";
        if (nextDate.compareTo(today) < 0) return "ПРОСРОЧ";
        if (nextDate.compareTo(in30) <= 0) return "скоро";
        return "OK";
    }

    private static long queryLong(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String nvl(String s) {
        return s == null ? "-" : s;
    }
}