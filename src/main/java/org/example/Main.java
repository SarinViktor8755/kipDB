package org.example;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class Main {

    public static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        Db.init();
        WorkflowDb.init();
        PositionDirectory.init();
        ChartDb.init();
        System.out.println("SQLite инициализирован: " + Db.getDatabasePath());
        System.out.println("Найдено приборов: " + Db.getAllDevices().size());
        System.out.println("Пользователей: " + Db.getUsersPublic().length() + " (включая администратора)");
        // Тестовые приборы автоматически не создаём: рабочая база должна оставаться без изменений.

//        ConsoleReport.printSummary();
//        ConsoleReport.printDevices(10);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api",     new ApiHandler());
        server.createContext("/photos",  new PhotoHandler());
        server.createContext("/",        new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Сервер запущен: http://localhost:" + PORT + "/");
        System.out.println("В локальной сети: http://<IP-этого-компьютера>:" + PORT + "/");
        System.out.println("Для остановки нажмите Ctrl+C");
    }
}
