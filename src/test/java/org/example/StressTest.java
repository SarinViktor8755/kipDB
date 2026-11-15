package org.example;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class StressTest {

    private static final int ITERATIONS = 20;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm_dd.MM.yy");
    private List<Long> createdDuringTestIds = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        Db.init();
        createdDuringTestIds.clear();
    }

    @After
    public void tearDown() throws Exception {
        // ВНИМАНИЕ: Если вы хотите, чтобы приборы ОСТАЛИСЬ в базе (чтобы в журнале были их имена),
        // просто закомментируйте этот блок. Сейчас он удаляет приборы, но оставляет audit_log.
        if (createdDuringTestIds.isEmpty()) return;

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:kip.db");
             Statement s = c.createStatement()) {
            String ids = String.join(",", createdDuringTestIds.stream().map(String::valueOf).toArray(String[]::new));
            s.execute("DELETE FROM devices WHERE id IN (" + ids + ")");
        } catch (SQLException ignored) { }
    }

    /**
     * Вспомогательный метод: вручную пишет запись в аудит.
     * Используем локальное время, чтобы совпадало с Db.java.
     */
    private void logManually(Connection c, long deviceId, String action, String details) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log(device_id, changed_at, changed_by, action, details) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, deviceId);
            ps.setString(2, LocalDateTime.now().format(TS)); // Локальное время!
            ps.setString(3, "stress-test");
            ps.setString(4, action);
            ps.setString(5, details);
            ps.executeUpdate();
        }
    }

    private Result runSingleCycle(int cycleNumber) throws Exception {
        int devicesBefore = Db.getAllDevices().size();
        int auditBefore = Db.getAudit(Integer.MAX_VALUE).size();

        Device sharedDevice = new Device();
        sharedDevice.name = "Общий прибор (тест)";
        long sharedId = Db.createDevice(sharedDevice, "stress-test");
        synchronized (createdDuringTestIds) {
            createdDuringTestIds.add(sharedId);
        }

        int createThreads = 5;
        int updateThreads = 5;
        int readThreads = 2;
        int totalThreads = createThreads + updateThreads + readThreads;
        int operationsPerCreateThread = 10;
        int operationsPerUpdateThread = 10;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(totalThreads);

        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger updatedCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger readCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        // 1. Создание
        for (int i = 0; i < createThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < operationsPerCreateThread; j++) {
                        Device d = new Device();
                        d.name = "Прибор T" + threadId + "_" + j;
                        d.serialNumber = "SN-" + threadId + "-" + j;
                        long newId = Db.createDevice(d, "stress-test");
                        synchronized (createdDuringTestIds) {
                            createdDuringTestIds.add(newId);
                        }
                        createdCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            }).start();
        }

        // 2. Обновление
        for (int i = 0; i < updateThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < operationsPerUpdateThread; j++) {
                        Device copy = Db.getDevice(sharedId);
                        if (copy == null) break;
                        copy.location = "Обновлено T" + threadId + "_" + j;
                        try {
                            Db.updateDevice(copy, "stress-test");
                            updatedCount.incrementAndGet();
                        } catch (OptimisticLockException e) {
                            conflictCount.incrementAndGet();
                            try (Connection c = DriverManager.getConnection("jdbc:sqlite:kip.db")) {
                                logManually(c, sharedId, "CONFLICT",
                                        "Попытка обновления (версия " + copy.version + ") не удалась");
                            } catch (SQLException ignored) {}
                        }
                    }
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            }).start();
        }

        // 3. Чтение
        for (int i = 0; i < readThreads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 30; j++) {
                        List<Device> all = Db.getAllDevices();
                        Db.getAudit(50);
                        readCount.addAndGet(all.size());
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            }).start();
        }

        start.countDown();
        finish.await();

        Result r = new Result();
        r.cycle = cycleNumber;
        r.created = createdCount.get();
        r.updated = updatedCount.get();
        r.conflicts = conflictCount.get();
        r.read = readCount.get();
        r.errors = unexpectedErrors.get();

        int devicesAfter = Db.getAllDevices().size();
        int expectedNewDevices = 1 + (createThreads * operationsPerCreateThread);
        assertEquals("Цикл " + cycleNumber + ": неверное количество приборов",
                devicesBefore + expectedNewDevices, devicesAfter);

        int totalUpdateAttempts = updateThreads * operationsPerUpdateThread;
        assertEquals("Цикл " + cycleNumber + ": сумма обновлений и конфликтов",
                totalUpdateAttempts, r.updated + r.conflicts);

        Device finalShared = Db.getDevice(sharedId);
        assertEquals("Цикл " + cycleNumber + ": версия общего прибора",
                1 + r.updated, finalShared.version);

        int auditAfter = Db.getAudit(Integer.MAX_VALUE).size();
        int expectedNewAudit = 1 + r.created + r.updated + r.conflicts;
        assertEquals("Цикл " + cycleNumber + ": записей в журнале (CREATE + UPDATE + CONFLICT)",
                auditBefore + expectedNewAudit, auditAfter);

        assertEquals("Цикл " + cycleNumber + ": неожиданные ошибки", 0, r.errors);

        // Тест корзины
        Db.deleteDevice(sharedId, "stress-test");
        assertTrue("Прибор должен исчезнуть из основного списка",
                Db.getAllDevices().stream().noneMatch(d -> d.id == sharedId));
        assertTrue("Прибор должен появиться в корзине",
                Db.getDeletedDevices().stream().anyMatch(d -> d.id == sharedId));

        Db.restoreDevice(sharedId, "stress-test");
        assertTrue("Прибор должен вернуться из корзины",
                Db.getAllDevices().stream().anyMatch(d -> d.id == sharedId));

        auditAfter = Db.getAudit(Integer.MAX_VALUE).size();
        expectedNewAudit += 2; // DELETE + RESTORE
        assertEquals("Цикл " + cycleNumber + ": записей в журнале (с DELETE и RESTORE)",
                auditBefore + expectedNewAudit, auditAfter);

        return r;
    }

    @Test
    public void testHeavyConcurrentLoad20Times() throws Exception {
        List<Result> results = new ArrayList<>();
        System.out.println("=== ЗАПУСК СТРЕСС-ТЕСТА " + ITERATIONS + " РАЗ ===");
        long globalStart = System.currentTimeMillis();

        for (int i = 1; i <= ITERATIONS; i++) {
            long cycleStart = System.currentTimeMillis();
            Result r = runSingleCycle(i);
            long cycleEnd = System.currentTimeMillis();
            r.durationMs = cycleEnd - cycleStart;
            results.add(r);
            System.out.printf("Цикл %2d: создано=%d, обновлено=%d, конфликтов=%d, время=%d мс%n",
                    i, r.created, r.updated, r.conflicts, r.durationMs);
        }

        long globalEnd = System.currentTimeMillis();
        double avgUpdated = results.stream().mapToInt(r -> r.updated).average().orElse(0);
        double avgConflicts = results.stream().mapToInt(r -> r.conflicts).average().orElse(0);
        long totalUpdated = results.stream().mapToLong(r -> r.updated).sum();
        long totalConflicts = results.stream().mapToLong(r -> r.conflicts).sum();
        long totalCreated = results.stream().mapToLong(r -> r.created).sum();
        long totalErrors = results.stream().mapToLong(r -> r.errors).sum();
        long avgDuration = (globalEnd - globalStart) / ITERATIONS;

        System.out.println("\n==========================================");
        System.out.println("         ИТОГОВАЯ СТАТИСТИКА (" + ITERATIONS + " циклов)");
        System.out.println("==========================================");
        System.out.println("Всего создано приборов:  " + totalCreated);
        System.out.println("Всего обновлений:        " + totalUpdated);
        System.out.println("Всего конфликтов:        " + totalConflicts);
        System.out.println("Всего ошибок БД:         " + totalErrors);
        System.out.println("Среднее обновлений/цикл: " + String.format("%.2f", avgUpdated));
        System.out.println("Среднее конфликтов/цикл: " + String.format("%.2f", avgConflicts));
        System.out.println("Среднее время цикла:     " + avgDuration + " мс");
        System.out.println("Общее время теста:       " + (globalEnd - globalStart) + " мс");
        System.out.println("==========================================");

        assertEquals("Ни одного сбоя БД за все циклы", 0, totalErrors);
        assertEquals("Всего создано", ITERATIONS * 50, totalCreated);
        assertEquals("Сумма обновлений и конфликтов", ITERATIONS * 50, totalUpdated + totalConflicts);
    }

    private static class Result {
        int cycle;
        int created;
        int updated;
        int conflicts;
        int read;
        int errors;
        long durationMs;
    }
}