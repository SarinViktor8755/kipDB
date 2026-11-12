package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PhotoHandler implements HttpHandler {

    private static final Path PHOTOS_DIR = Paths.get("photos").toAbsolutePath();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String filename = path.substring("/photos/".length());

        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            sendText(ex, 400, "Bad request");
            return;
        }

        Path file = PHOTOS_DIR.resolve(filename).normalize();
        if (!file.startsWith(PHOTOS_DIR) || !Files.isRegularFile(file)) {
            sendText(ex, 404, "Not found");
            return;
        }

        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        String mime = switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
        byte[] bytes = Files.readAllBytes(file);

        ex.getResponseHeaders().set("Content-Type", mime);

        // ВАЖНО: Запрещаем браузеру жестко кэшировать файл,
        // чтобы при удалении фото оно пропадало из интерфейса
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");

        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }
}