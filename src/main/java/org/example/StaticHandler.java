package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class StaticHandler implements HttpHandler {

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry(".html", "text/html; charset=UTF-8"),
            Map.entry(".css",  "text/css; charset=UTF-8"),
            Map.entry(".js",   "application/javascript; charset=UTF-8"),
            Map.entry(".json", "application/json; charset=UTF-8"),
            Map.entry(".png",  "image/png"),
            Map.entry(".jpg",  "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".svg",  "image/svg+xml"),
            Map.entry(".ico",  "image/x-icon")
    );

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..") || path.contains("\\")) {
            sendText(ex, 400, "Bad request");
            return;
        }
        String resource = "/public" + path;
        InputStream in = StaticHandler.class.getResourceAsStream(resource);
        if (in == null) {
            sendText(ex, 404, "Not found: " + path);
            return;
        }
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", mimeFor(path));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return MIME.getOrDefault(path.substring(dot).toLowerCase(), "application/octet-stream");
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }
}