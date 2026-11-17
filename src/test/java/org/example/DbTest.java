package org.example;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class DbTest {

    // Используем тестовую БД, чтобы не затронуть реальные данные
    private static final String TEST_DB = "jdbc:sqlite:test_kip.db";

    @Before
    public void setUp() throws Exception {
        // Очищаем тестовую БД перед каждым тестом
        try (Connection c = DriverManager.getConnection(TEST_DB);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS devices");
            s.execute("DROP TABLE IF EXISTS audit_log");
            s.execute("DROP TABLE IF EXISTS users");
        } catch (SQLException ignored) { }

        // Инициализируем схему
        Db.init();
    }

    @After
    public void tearDown() throws Exception {
        // Очищаем после теста
        try (Connection c = DriverManager.getConnection(TEST_DB);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS devices");
            s.execute("DROP TABLE IF EXISTS audit_log");
            s.execute("DROP TABLE IF EXISTS users");
        } catch (SQLException ignored) { }
    }

    // ==========================================
    // 1. БАЗОВЫЕ ТЕСТЫ (Создание и Чтение)
    // ==========================================

    @Test
    public void testCreateAndGetDevice() throws Exception {
        Device d = new Device();
        d.name = "Тестовый прибор";
        d.serialNumber = "123456";
        d.location = "Цех №1";
        d.lastVerification = "2025-01-01";
        d.nextVerification = "2026-01-01";

        long id = Db.createDevice(d, "tester");
        assertTrue("ID должен быть > 0", id > 0);

        Device loaded = Db.getDevice(id);
        assertNotNull(loaded);
        assertEquals("Тестовый прибор", loaded.name);
        assertEquals("123456", loaded.serialNumber);
        assertEquals(1, loaded.version);
    }

    @Test
    public void testUpdateDeviceIncrementsVersion() throws Exception {
        Device d = new Device();
        d.name = "Прибор для обновления";
        d.serialNumber = "111111";
        long id = Db.createDevice(d, "tester");

        Device loaded = Db.getDevice(id);
        loaded.name = "Обновлённый прибор";
        loaded.location = "Цех №2";

        boolean ok = Db.updateDevice(loaded, "tester");
        assertTrue(ok);

        Device after = Db.getDevice(id);
        assertEquals("Обновлённый прибор", after.name);
        assertEquals(2, after.version);
    }

    @Test
    public void testDeleteAndRestore() throws Exception {
        Device d = new Device();
        d.name = "Удаляемый прибор";
        long id = Db.createDevice(d, "tester");

        assertTrue(Db.deleteDevice(id, "tester"));

        // В обычном списке его нет
        List<Device> all = Db.getAllDevices();
        assertTrue(all.stream().noneMatch(x -> x.id == id));

        // В корзине — есть
        List<Device> deleted = Db.getDeletedDevices();
        assertTrue(deleted.stream().anyMatch(x -> x.id == id));

        // Восстановление
        assertTrue(Db.restoreDevice(id, "tester"));
        all = Db.getAllDevices();
        assertTrue(all.stream().anyMatch(x -> x.id == id));
    }

    // ==========================================
    // 2. ТЕСТЫ НА ОШИБКИ (Неверные ID)
    // ==========================================

    @Test
    public void testGetNonexistentDeviceReturnsNull() throws Exception {
        Device d = Db.getDevice(99999);
        assertNull(d);
    }

    @Test
    public void testDeleteNonexistentReturnsFalse() throws Exception {
        boolean ok = Db.deleteDevice(99999, "tester");
        assertFalse(ok);
    }

    @Test
    public void testUpdateNonexistentDevice() throws Exception {
        Device d = new Device();
        d.id = 99999;
        d.name = "Нет такого";
        boolean ok = Db.updateDevice(d, "tester");
        assertFalse(ok);
    }

    // ==========================================
    // 3. ТЕСТЫ КОНФЛИКТОВ (Оптимистичная блокировка)
    // ==========================================

    @Test(expected = OptimisticLockException.class)
    public void testOptimisticLockConflict() throws Exception {
        Device d = new Device();
        d.name = "Конфликтный прибор";
        long id = Db.createDevice(d, "tester");

        // Два пользователя получают копию с version=1
        Device user1 = Db.getDevice(id);
        Device user2 = Db.getDevice(id);

        // user1 обновляет — версия становится 2
        user1.name = "Изменено user1";
        Db.updateDevice(user1, "user1");

        // user2 пытается обновить с устаревшей version=1 — должен быть конфликт
        user2.name = "Изменено user2";
        Db.updateDevice(user2, "user2"); // Должно выбросить OptimisticLockException
    }

    @Test
    public void testConcurrentUpdatesOnlyOneWins() throws Exception {
        Device d = new Device();
        d.name = "Спорный прибор";
        long id = Db.createDevice(d, "tester");

        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    // Каждый поток читает свою копию с version=1
                    Device copy = Db.getDevice(id);
                    copy.location = "Поток " + idx;

                    start.await(); // Ждём одновременного старта
                    try {
                        Db.updateDevice(copy, "user" + idx);
                        successCount.incrementAndGet();
                    } catch (OptimisticLockException e) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finish.countDown();
                }
            }).start();
        }

        start.countDown();
        finish.await();

        // Должен победить ровно 1 поток, остальные получат конфликт
        assertEquals("Должен победить 1 поток", 1, successCount.get());
        assertEquals("Остальные 4 должны получить конфликт", 4, conflictCount.get());

        // Версия должна вырасти на 1
        assertEquals(2, Db.getDevice(id).version);
    }

    // ==========================================
    // 4. ТЕСТЫ ЖУРНАЛА И ВЕРСИЙ
    // ==========================================

    @Test
    public void testAuditLogRecordsCreateAndUpdate() throws Exception {
        Device d = new Device();
        d.name = "Аудит-прибор";
        long id = Db.createDevice(d, "tester");

        Device loaded = Db.getDevice(id);
        loaded.name = "Аудит-прибор v2";
        Db.updateDevice(loaded, "tester");

        List<String[]> audit = Db.getAudit(10);
        assertFalse(audit.isEmpty());

        boolean hasCreate = audit.stream().anyMatch(r -> "CREATE".equals(r[2]));
        boolean hasUpdate = audit.stream().anyMatch(r -> "UPDATE".equals(r[2]));

        assertTrue("Должна быть запись CREATE", hasCreate);
        assertTrue("Должна быть запись UPDATE", hasUpdate);
    }

    @Test
    public void testVersionIsMonotonic() throws Exception {
        Device d = new Device();
        d.name = "Версионный";
        long id = Db.createDevice(d, "tester");
        assertEquals(1, Db.getDevice(id).version);

        for (int i = 2; i <= 5; i++) {
            Device cur = Db.getDevice(id);
            cur.location = "Шаг " + i;
            Db.updateDevice(cur, "tester");
            assertEquals("Версия должна расти", i, Db.getDevice(id).version);
        }
    }
}


============================================================
