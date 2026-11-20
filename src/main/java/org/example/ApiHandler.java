package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

public class ApiHandler implements HttpHandler {

    private static final Map<String, AuthSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<AuthSession> CURRENT_SESSION = new ThreadLocal<>();
    private static final long SESSION_TTL_MS = 12L * 60 * 60 * 1000;

    private static final class AuthSession {
        final long userId; final String login; final String name; final String role; final long expiresAt;
        AuthSession(long userId, String login, String name, String role) {
            this.userId=userId; this.login=login; this.name=name; this.role=role;
            this.expiresAt=System.currentTimeMillis()+SESSION_TTL_MS;
        }
        JSONObject json() { return new JSONObject().put("id", userId).put("login", login).put("name", name).put("role", role); }
    }

    private static String currentUserName() {
        AuthSession s = CURRENT_SESSION.get(); return s == null ? "unknown" : s.name;
    }
    private static AuthSession findSession(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String item : cookie.split(";")) {
            String[] kv = item.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("KIPSESSION")) {
                AuthSession s = SESSIONS.get(kv[1]);
                if (s != null && s.expiresAt > System.currentTimeMillis()) return s;
                if (s != null) SESSIONS.remove(kv[1]);
            }
        }
        return null;
    }
    private static void setSessionCookie(HttpExchange ex, String token) {
        ex.getResponseHeaders().add("Set-Cookie", "KIPSESSION=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }
    private static void clearSessionCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", "KIPSESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    private static final long MAX_PHOTO_BYTES = 20L * 1024 * 1024;
    private static final long TARGET_PHOTO_BYTES = 1_500_000;
    private static final int  PHOTO_MAX_SIDE = 1600;
    private static final int  THUMB_MAX_SIDE = 160;

    private static final Path PHOTOS_DIR = Paths.get("photos").toAbsolutePath();

    static {
        ImageIO.setUseCache(false);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        try {
            // Публичные методы авторизации: список пользователей, вход, выход и проверка сессии.
            if (path.equals("/api/auth/users") && "GET".equals(method)) {
                sendJson(ex, 200, Db.getUsersPublic()); return;
            }
            if (path.equals("/api/auth/me") && "GET".equals(method)) {
                AuthSession existing = findSession(ex);
                sendJson(ex, 200, existing == null ? new JSONObject().put("authenticated", false)
                        : new JSONObject().put("authenticated", true).put("user", existing.json()));
                return;
            }
            if (path.equals("/api/auth/login") && "POST".equals(method)) {
                JSONObject body = readJson(ex);
                String login = body.optString("login", "").trim();
                String password = body.optString("password", "");
                AuthSession session;
                if ("guest".equalsIgnoreCase(login) || "Гость".equalsIgnoreCase(login)) {
                    session = new AuthSession(0, "guest", "Гость", "guest");
                } else {
                    JSONObject account = Db.findUserForLogin(login);
                    if (account == null || !AuthUtil.verify(password, account.optString("passwordHash", ""))) {
                        sendJson(ex, 401, new JSONObject().put("error", "Неверный пользователь или пароль")); return;
                    }
                    session = new AuthSession(account.getLong("id"), account.getString("login"), account.getString("name"), account.getString("role"));
                }
                long now = System.currentTimeMillis();
                SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
                String token = UUID.randomUUID().toString().replace("-", "");
                SESSIONS.put(token, session); setSessionCookie(ex, token);
                sendJson(ex, 200, new JSONObject().put("authenticated", true).put("user", session.json())); return;
            }
            if (path.equals("/api/auth/logout") && "POST".equals(method)) {
                String cookie = ex.getRequestHeaders().getFirst("Cookie");
                if (cookie != null) for (String item : cookie.split(";")) {
                    String[] kv = item.trim().split("=", 2);
                    if (kv.length == 2 && kv[0].equals("KIPSESSION")) SESSIONS.remove(kv[1]);
                }
                clearSessionCookie(ex); sendJson(ex, 200, new JSONObject().put("status", "ok")); return;
            }

            AuthSession session = findSession(ex);
            if (session == null) { sendJson(ex, 401, new JSONObject().put("error", "Требуется вход в систему")); return; }
            CURRENT_SESSION.set(session);
            if ("guest".equals(session.role) && !"GET".equals(method) && !"HEAD".equals(method)) {
                sendJson(ex, 403, new JSONObject().put("error", "Гость может только просматривать данные")); return;
            }

            // Отдельный список приборов для вкладки «График».
            if (path.equals("/api/chart")) { if (!"GET".equals(method)) { methodNotAllowed(ex); return; } sendJson(ex,200,ChartDb.list()); return; }
            if (path.equals("/api/chart/search")) {
                if (!"GET".equals(method)) { methodNotAllowed(ex); return; }
                String q="", query=ex.getRequestURI().getRawQuery();
                if(query!=null) for(String part:query.split("&")){int eq=part.indexOf('=');if(eq>=0&&"q".equals(part.substring(0,eq)))q=URLDecoder.decode(part.substring(eq+1),StandardCharsets.UTF_8);}
                sendJson(ex,200,ChartDb.search(q)); return;
            }
            if (path.equals("/api/chart/order")) { if (!"PUT".equals(method)) { methodNotAllowed(ex); return; } JSONArray ids=readJson(ex).optJSONArray("ids"); if(ids==null){sendJson(ex,400,new JSONObject().put("error","Не передан порядок приборов"));return;} ChartDb.reorder(ids);sendJson(ex,200,new JSONObject().put("status","ok"));return; }
            if (path.matches("/api/chart/[0-9]+")) { long id=Long.parseLong(path.substring("/api/chart/".length())); if("POST".equals(method)){ChartDb.add(id);sendJson(ex,201,new JSONObject().put("added",true));}else if("DELETE".equals(method))sendJson(ex,200,new JSONObject().put("removed",ChartDb.remove(id)));else methodNotAllowed(ex);return; }
            // Справочник позиций из Книга1 хранится в таблице основной базы kip.db.
            if (path.equals("/api/reference-positions")) {
                if ("GET".equals(method)) sendJson(ex, 200, PositionDirectory.list());
                else methodNotAllowed(ex);
                return;
            }
            if (path.matches("/api/reference-positions/[0-9]+")) {
                long positionId = Long.parseLong(path.substring("/api/reference-positions/".length()));
                if ("DELETE".equals(method)) {
                    if (PositionDirectory.delete(positionId)) sendJson(ex, 200, new JSONObject().put("deleted", true));
                    else sendJson(ex, 404, new JSONObject().put("error", "Позиция не найдена"));
                    return;
                }
                if (!"PUT".equals(method)) { methodNotAllowed(ex); return; }
                JSONObject body = readJson(ex);
                try {
                    JSONObject updated = PositionDirectory.update(positionId,
                            body.optString("position", ""), body.optString("description", ""), body.optString("equipment", ""));
                    if (updated == null) sendJson(ex, 404, new JSONObject().put("error", "Позиция не найдена"));
                    else sendJson(ex, 200, updated);
                } catch (IllegalArgumentException e) {
                    sendJson(ex, 400, new JSONObject().put("error", e.getMessage()));
                } catch (SQLException e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique"))
                        sendJson(ex, 409, new JSONObject().put("error", "Такая позиция уже существует"));
                    else throw e;
                }
                return;
            }

            if (path.equals("/api/users") || path.startsWith("/api/users/")) {
                if (!"admin".equals(session.role)) { sendJson(ex, 403, new JSONObject().put("error", "Только администратор может управлять пользователями")); return; }
                if (path.equals("/api/users") && "GET".equals(method)) { sendJson(ex, 200, Db.getUsersPublic()); return; }
                if (path.equals("/api/users") && "POST".equals(method)) {
                    JSONObject b = readJson(ex);
                    String name = b.optString("name", "").trim();
                    String password = b.optString("password", "");
                    if (name.isEmpty() || name.length() > 100) { sendJson(ex, 400, new JSONObject().put("error", "Укажите имя пользователя (до 100 символов)")); return; }
                    if (password.length() < 4) { sendJson(ex, 400, new JSONObject().put("error", "Пароль должен содержать не менее 4 символов")); return; }
                    if (name.equalsIgnoreCase("guest") || name.equalsIgnoreCase("гость")) { sendJson(ex, 400, new JSONObject().put("error", "Это имя зарезервировано системой")); return; }
                    try {
                        long id = Db.createUser(name, password, currentUserName());
                        sendJson(ex, 201, new JSONObject().put("id", id).put("name", name).put("role", "operator"));
                    } catch (SQLException e) {
                        if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique"))
                            sendJson(ex, 409, new JSONObject().put("error", "Пользователь с таким именем уже существует"));
                        else throw e;
                    }
                    return;
                }
                if ("DELETE".equals(method)) {
                    String idText = path.substring("/api/users".length());
                    if (!idText.startsWith("/") || idText.length() <= 1 || idText.substring(1).contains("/")) {
                        sendJson(ex, 404, new JSONObject().put("error", "Не указан пользователь")); return;
                    }
                    long userId = Long.parseLong(idText.substring(1));
                    if (userId == session.userId) { sendJson(ex, 400, new JSONObject().put("error", "Нельзя удалить текущую учётную запись администратора")); return; }
                    if (!Db.deleteUser(userId, currentUserName())) { sendJson(ex, 404, new JSONObject().put("error", "Пользователь не найден или защищён от удаления")); return; }
                    SESSIONS.entrySet().removeIf(entry -> entry.getValue().userId == userId);
                    sendJson(ex, 200, new JSONObject().put("status", "deleted")); return;
                }
                methodNotAllowed(ex); return;
            }

            if (path.equals("/api/export.csv")) { exportDevicesCsv(ex); return; }
            if (path.equals("/api/audit.csv"))  { exportAuditCsv(ex);   return; }

            if (path.startsWith("/api/devices/") && path.endsWith("/restore") && "POST".equals(method)) {
                String mid = path.substring("/api/devices/".length(), path.length() - "/restore".length());
                if (mid.isEmpty() || mid.contains("/")) {
                    sendJson(ex, 404, new JSONObject().put("error", "not found"));
                    return;
                }
                restoreDevice(ex, Long.parseLong(mid));
                return;
            }

            if (path.startsWith("/api/devices/") && path.endsWith("/photo") && "POST".equals(method)) {
                String mid = path.substring("/api/devices/".length(), path.length() - "/photo".length());
                if (mid.isEmpty() || mid.contains("/")) {
                    sendJson(ex, 404, new JSONObject().put("error", "not found"));
                    return;
                }
                uploadPhoto(ex, Long.parseLong(mid));
                return;
            }

            if (path.equals("/api/devices/deleted") && "GET".equals(method)) {
                listDeletedDevices(ex);
                return;
            }

            // API документов удалён вместе с таблицами документов.

            if (path.equals("/api/movements/batch")) {
                if (!"POST".equals(method)) { methodNotAllowed(ex); return; }
                JSONObject b = readJson(ex);
                JSONArray ids = b.optJSONArray("deviceIds");
                String eventDate = b.optString("eventDate", LocalDate.now().toString()).trim();
                String eventType = b.optString("eventType", "").trim();
                if (ids == null || ids.length() == 0 || eventType.isEmpty() || !eventDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    sendJson(ex, 400, new JSONObject().put("error", "Укажите приборы, тип операции и корректную дату")); return;
                }
                Db.addDeviceMovementsBatch(ids, eventDate, eventType, emptyToNull(b.optString("fromPlace", "")),
                        emptyToNull(b.optString("toPlace", "")), emptyToNull(b.optString("note", "")), currentUserName());
                sendJson(ex, 201, new JSONObject().put("recorded", ids.length()));
                return;
            }

            if (path.equals("/api/movements/cancel")) {
                if (!"POST".equals(method)) { methodNotAllowed(ex); return; }
                JSONObject body = readJson(ex); JSONArray ids = body.optJSONArray("deviceIds");
                String batchId = body.optString("batchId", ""); long movementId = body.optLong("movementId", 0);
                if (ids == null || ids.length() == 0 || (batchId.isBlank() && movementId <= 0)) { sendJson(ex,400,new JSONObject().put("error","Укажите перемещение и приборы для отмены")); return; }
                int count = Db.cancelMovement(batchId, movementId, ids);
                sendJson(ex,200,new JSONObject().put("cancelled",count)); return;
            }

            if (path.equals("/api/movements") && "GET".equals(method)) {
                String search = "";
                boolean all = false;
                String query = ex.getRequestURI().getRawQuery();
                if (query != null) for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    String key = eq >= 0 ? part.substring(0, eq) : part;
                    String value = eq >= 0 ? part.substring(eq + 1) : "";
                    if ("q".equals(key)) search = URLDecoder.decode(value, StandardCharsets.UTF_8);
                    if ("all".equals(key)) all = "true".equalsIgnoreCase(value) || "1".equals(value);
                }
                JSONArray rows = Db.getMovements(search, all ? 0 : 150);
                sendJsonGzip(ex, 200, rows);
                return;
            }

            if (path.matches("/api/devices/[0-9]+/movements")) {
                long deviceId = Long.parseLong(path.split("/")[3]);
                if ("GET".equals(method)) {
                    sendJson(ex, 200, new JSONObject().put("movements", Db.getDeviceMovementHistory(deviceId)));
                } else if ("POST".equals(method)) {
                    JSONObject b=readJson(ex);
                    String eventDate=b.optString("eventDate", LocalDate.now().toString()).trim();
                    String eventType=b.optString("eventType","").trim();
                    if (eventType.isEmpty() || !eventDate.matches("\\d{4}-\\d{2}-\\d{2}")) { sendJson(ex,400,new JSONObject().put("error","Укажите тип операции и корректную дату")); return; }
                    Db.addDeviceMovement(deviceId,eventDate,eventType,emptyToNull(b.optString("fromPlace","")),emptyToNull(b.optString("toPlace","")),emptyToNull(b.optString("note","")),currentUserName());
                    sendJson(ex,201,new JSONObject().put("movements",Db.getDeviceMovementHistory(deviceId)));
                } else methodNotAllowed(ex);
                return;
            }

            if (path.equals("/api/devices/page") && "GET".equals(method)) {
                listDevicesPage(ex);
                return;
            }

            if (path.equals("/api/devices")) {
                if ("GET".equals(method)) listDevices(ex);
                else if ("POST".equals(method)) createDevice(ex);
                else methodNotAllowed(ex);
                return;
            }

            if (path.startsWith("/api/devices/")) {
                String tail = path.substring("/api/devices/".length());
                if (tail.isEmpty() || tail.contains("/")) {
                    sendJson(ex, 404, new JSONObject().put("error", "not found"));
                    return;
                }
                long id = Long.parseLong(tail);
                switch (method) {
                    case "GET"    -> getDevice(ex, id);
                    case "PUT"    -> updateDevice(ex, id);
                    case "DELETE" -> deleteDevice(ex, id);
                    default       -> methodNotAllowed(ex);
                }
                return;
            }

            if (path.equals("/api/audit")) { audit(ex); return; }
            if (path.equals("/api/stats")) { stats(ex); return; }

            sendJson(ex, 404, new JSONObject().put("error", "unknown endpoint"));
        } catch (NumberFormatException e) {
            sendJson(ex, 400, new JSONObject().put("error", "invalid id"));
        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(ex, 500, new JSONObject().put("error", "db error: " + e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, new JSONObject().put("error", e.getMessage()));
        } finally {
            CURRENT_SESSION.remove();
            ex.close();
        }
    }

    private void listDeletedDevices(HttpExchange ex) throws IOException, SQLException {
        List<Device> devices = Db.getDeletedDevices();
        JSONArray arr = new JSONArray();
        for (Device d : devices) arr.put(toJson(d));
        // Корзина — тоже потенциально большой массив, сжимаем.
        sendJsonGzip(ex, 200, arr);
    }

    private void restoreDevice(HttpExchange ex, long id) throws IOException, SQLException {
        boolean ok = Db.restoreDevice(id, currentUserName());
        if (!ok) {
            sendJson(ex, 404, new JSONObject().put("error", "not found"));
        } else {
            sendJson(ex, 200, new JSONObject().put("status", "restored"));
        }
    }

    private void uploadPhoto(HttpExchange ex, long id) throws IOException, SQLException {
        Device d = Db.getDevice(id);
        if (d == null) {
            sendJson(ex, 404, new JSONObject().put("error", "device not found"));
            return;
        }

        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = readLimited(in, MAX_PHOTO_BYTES);
        }

        if (raw != null) {
            System.out.printf("[HTTP IN ] %s %s | Получено от клиента: %d байт (фото)%n", ex.getRequestMethod(), ex.getRequestURI().getPath(), raw.length);
        }

        if (raw == null) { sendJson(ex, 413, new JSONObject().put("error", "file too large")); return; }
        if (raw.length == 0) { sendJson(ex, 400, new JSONObject().put("error", "empty body")); return; }

        BufferedImage src;
        try { src = ImageIO.read(new ByteArrayInputStream(raw)); } catch (IOException e) { src = null; }
        if (src == null) { sendJson(ex, 415, new JSONObject().put("error", "unsupported image format")); return; }

        Files.createDirectories(PHOTOS_DIR);

        String base = "photo_" + id + "_" + System.currentTimeMillis();
        byte[] fullBytes = compressJpeg(src, PHOTO_MAX_SIDE, TARGET_PHOTO_BYTES);
        String fullName = base + ".jpg";
        Path fullPath = PHOTOS_DIR.resolve(fullName);
        Files.write(fullPath, fullBytes);

        byte[] thumbBytes = compressJpeg(src, THUMB_MAX_SIDE, Long.MAX_VALUE);
        String thumbName = base + "_thumb.jpg";
        Path thumbPath = PHOTOS_DIR.resolve(thumbName);
        Files.write(thumbPath, thumbBytes);

        deletePhotoFilesIfAny(d);

        String photoPath = "photos/" + fullName;
        String thumbRel  = "photos/" + thumbName;
        Db.updatePhotoPaths(id, photoPath, thumbRel, currentUserName());

        JSONObject resp = toJson(Db.getDevice(id));
        resp.put("changes", "фото " + fullBytes.length / 1024 + " КБ, миниатюра " + thumbBytes.length / 1024 + " КБ");
        sendJson(ex, 200, resp);
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] compressJpeg(BufferedImage src, int maxSide, long target) throws IOException {
        int w = src.getWidth(), h = src.getHeight();
        if (w > maxSide || h > maxSide) {
            if (w >= h) { h = Math.round(h * (float) maxSide / w); w = maxSide; }
            else        { w = Math.round(w * (float) maxSide / h); h = maxSide; }
        }
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }

        float[] qualities = {0.85f, 0.75f, 0.65f, 0.55f, 0.45f, 0.35f};
        byte[] best = null;
        for (float q : qualities) {
            byte[] bytes = writeJpeg(rgb, q);
            best = bytes;
            if (bytes.length <= target) break;
        }
        return best;
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) throw new IOException("no JPEG writer");
        ImageWriter writer = it.next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private static void deletePhotoFilesIfAny(Device d) {
        deleteIfInsidePhotos(d.photoPath);
        deleteIfInsidePhotos(d.photoThumbPath);
    }

    private static void deleteIfInsidePhotos(String rel) {
        if (rel == null || !rel.startsWith("photos/")) return;
        String name = rel.substring("photos/".length());
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return;
        try { Files.deleteIfExists(PHOTOS_DIR.resolve(name)); } catch (IOException ignored) { }
    }

    private void listDevicesPage(HttpExchange ex) throws IOException, SQLException {
        Map<String,String> params = new java.util.HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? part : part.substring(0,eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(eq < 0 ? "" : part.substring(eq+1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        int limit = parseBoundedInt(params.get("limit"), 150, 1, 500);
        int offset = parseBoundedInt(params.get("offset"), 0, 0, Integer.MAX_VALUE);
        JSONObject filters;
        try { filters = new JSONObject(params.getOrDefault("filters", "{}")); }
        catch (Exception ignored) { filters = new JSONObject(); }
        JSONObject result = Db.getDevicesPage(limit, offset, params.get("q"), params.get("status"),
                filters, params.get("sort"), params.get("direction"));
        String etag = "\"devices-page-" + Db.getDevicesRevision() + "-" + Integer.toHexString(params.toString().hashCode()) + "\"";
        ex.getResponseHeaders().set("ETag", etag);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        sendJsonGzip(ex, 200, result);
    }

    private static int parseBoundedInt(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
        catch (Exception ignored) { return fallback; }
    }

    private void listDevices(HttpExchange ex) throws IOException, SQLException {
        // ETag вычисляется по ревизии SQLite, а не по времени запроса.
        // Триггеры БД меняют её при создании, редактировании, удалении и восстановлении прибора.
        String etag = "\"devices-" + Db.getDevicesRevision() + "\"";
        ex.getResponseHeaders().set("ETag", etag);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        String clientTag = ex.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(clientTag)) {
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }

        List<Device> devices = Db.getAllDevices();
        JSONArray arr = new JSONArray();
        for (Device d : devices) arr.put(toJson(d));
        // Основной массив — самый большой ответ, сжимаем. ETag сохраняется и при gzip.
        sendJsonGzip(ex, 200, arr);
    }

    private void getDevice(HttpExchange ex, long id) throws IOException, SQLException {
        Device d = Db.getDevice(id);
        if (d == null) sendJson(ex, 404, new JSONObject().put("error", "not found"));
        else sendJson(ex, 200, toJson(d));
    }

    private void createDevice(HttpExchange ex) throws IOException, SQLException {
        JSONObject body = readJson(ex);
        Device d = fromJson(body, 0);
        long id = Db.createDevice(d, currentUserName());
        d.id = id;
        sendJson(ex, 201, toJson(Db.getDevice(id)));
    }

    private void updateDevice(HttpExchange ex, long id) throws IOException, SQLException {
        JSONObject body = readJson(ex);

        Device oldDevice = Db.getDevice(id);
        if (oldDevice == null) {
            sendJson(ex, 404, new JSONObject().put("error", "not found"));
            return;
        }

        Device d = fromJson(body, id);
        boolean forceClearPhoto = body.has("photoPath") && body.isNull("photoPath");

        try {
            boolean ok = Db.updateDevice(d, currentUserName(), forceClearPhoto);
            if (!ok) {
                sendJson(ex, 404, new JSONObject().put("error", "not found"));
            } else {
                if (forceClearPhoto) {
                    deletePhotoFilesIfAny(oldDevice);
                }
                sendJson(ex, 200, toJson(Db.getDevice(id)));
            }
        } catch (OptimisticLockException e) {
            sendJson(ex, 409, new JSONObject()
                    .put("error", "conflict")
                    .put("message", "Прибор уже был изменён другим пользователем. Обновите данные и попробуйте снова.")
                    .put("expectedVersion", e.expectedVersion)
                    .put("actualVersion", e.actualVersion));
        }
    }

    private void deleteDevice(HttpExchange ex, long id) throws IOException, SQLException {
        boolean ok = Db.deleteDevice(id, currentUserName());
        if (!ok) {
            sendJson(ex, 404, new JSONObject().put("error", "not found"));
        } else {
            ex.sendResponseHeaders(204, -1);
        }
    }

    private void audit(HttpExchange ex) throws IOException, SQLException {
        int limit = 100;
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                if (part.startsWith("limit=")) {
                    try { limit = Integer.parseInt(part.substring(6)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        List<String[]> rows = Db.getAudit(limit);
        JSONArray arr = new JSONArray();
        for (String[] r : rows) {
            arr.put(new JSONObject()
                    .put("when", r[0] == null ? "" : r[0])
                    .put("who", r[1] == null ? "" : r[1])
                    .put("action", r[2] == null ? "" : r[2])
                    .put("details", r[3] == null ? "" : r[3])
                    .put("name", r[4] == null ? "—" : r[4])
                    .put("serialNumber", r[5] == null ? "—" : r[5])
                    .put("deviceId", r.length > 6 && r[6] != null ? Long.parseLong(r[6]) : JSONObject.NULL));
        }
        // Вместо sendJson используем sendJsonGzip, так как журнал может быть большим
        sendJsonGzip(ex, 200, arr);
    }

    private void stats(HttpExchange ex) throws IOException, SQLException {
        List<Device> all = Db.getAllDevices();
        LocalDate today = LocalDate.now();
        LocalDate in30 = today.plusDays(30);
        int overdue = 0, soon = 0, ok = 0, positions = 0, warehouse = 0, kip = 0;
        for (Device d : all) {
            if (d.nextVerification != null && !d.nextVerification.isEmpty()) {
                LocalDate nv = LocalDate.parse(d.nextVerification);
                if (nv.isBefore(today)) overdue++;
                else if (!nv.isAfter(in30)) soon++;
                else ok++;
            }
            JSONObject details;
            try { details = new JSONObject(d.detailsJson == null ? "{}" : d.detailsJson); }
            catch (Exception ignored) { details = new JSONObject(); }
            String workflow = details.optString("workflowStatus", "");
            if ("INSTALLED".equals(workflow) || (!"WRITTEN_OFF".equals(workflow) && !"WAREHOUSE".equals(workflow) && !"KIP".equals(workflow) && d.location != null && !d.location.isBlank())) positions++;
            else if ("WAREHOUSE".equals(workflow) || (workflow.isBlank() && (d.location == null || d.location.isBlank()))) warehouse++;
            else if ("KIP".equals(workflow)) kip++;
        }
        sendJson(ex, 200, new JSONObject()
                .put("total", all.size())
                .put("overdue", overdue)
                .put("soon", soon)
                .put("ok", ok)
                .put("positions", positions)
                .put("warehouse", warehouse)
                .put("kip", kip));
    }

    private void exportDevicesCsv(HttpExchange ex) throws IOException, SQLException {
        List<Device> all = Db.getAllDevices();
        StringBuilder sb = new StringBuilder();
        sb.append("ID;Заводской номер;Наименование;Расположение;Последняя поверка;Следующая поверка\r\n");
        for (Device d : all) {
            sb.append(d.id).append(';')
                    .append(csv(d.serialNumber)).append(';')
                    .append(csv(d.name)).append(';')
                    .append(csv(d.location)).append(';')
                    .append(csv(d.lastVerification)).append(';')
                    .append(csv(d.nextVerification)).append("\r\n");
        }
        sendCsv(ex, "devices.csv", sb.toString());
    }

    private void exportAuditCsv(HttpExchange ex) throws IOException, SQLException {
        List<String[]> rows = Db.getAudit(Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        sb.append("Когда;Кто;Действие;Наименование;Зав. номер;Детали\r\n");
        for (String[] r : rows) {
            sb.append(csv(r[0])).append(';')
                    .append(csv(r[1])).append(';')
                    .append(csv(r[2])).append(';')
                    .append(csv(r[4])).append(';')
                    .append(csv(r[5])).append(';')
                    .append(csv(r[3])).append("\r\n");
        }
        sendCsv(ex, "audit.csv", sb.toString());
    }

    private void sendCsv(HttpExchange ex, String filename, String body) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write("\uFEFF".getBytes(StandardCharsets.UTF_8));
        buf.write(body.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = buf.toByteArray();

        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }

        System.out.printf("[HTTP OUT] %s %s | Отправлено клиенту: %d байт (CSV)%n", ex.getRequestMethod(), ex.getRequestURI().getPath(), bytes.length);
    }

    private static JSONObject toJson(Device d) {
        JSONObject result;
        try { result = new JSONObject(d.detailsJson == null || d.detailsJson.isBlank() ? "{}" : d.detailsJson); }
        catch (Exception ignored) { result = new JSONObject(); }
        result.put("id", d.id).put("serialNumber", d.serialNumber == null ? "" : d.serialNumber)
                .put("name", d.name == null ? "" : d.name).put("location", d.location == null ? "" : d.location)
                .put("lastVerification", d.lastVerification == null ? "" : d.lastVerification)
                .put("nextVerification", d.nextVerification == null ? "" : d.nextVerification)
                .put("photoPath", (d.photoPath == null || d.photoPath.trim().isEmpty()) ? JSONObject.NULL : d.photoPath)
                .put("photoThumbPath", (d.photoThumbPath == null || d.photoThumbPath.trim().isEmpty()) ? JSONObject.NULL : d.photoThumbPath)
                .put("version", d.version).put("createdAt", d.createdAt == null ? "" : d.createdAt);
        return result;
    }

    private static Device fromJson(JSONObject o, long id) {
        Device d = new Device(); d.id = id;
        d.serialNumber = emptyToNull(o.optString("serialNumber", null)); d.name = o.optString("name", null);
        d.location = o.optString("location", null); d.lastVerification = emptyToNull(o.optString("lastVerification", null));
        d.nextVerification = emptyToNull(o.optString("nextVerification", null)); d.photoPath = emptyToNull(o.optString("photoPath", null));
        d.photoThumbPath = emptyToNull(o.optString("photoThumbPath", null)); d.version = o.optInt("version", 0);
        JSONObject details = new JSONObject();
        String[] keys = {"sensorType","unit","scaleMin","scaleMax","category","mpi","accuracyClass","redLine","writeOff","deferredVerification","movement","rowNumber","groupName","positionName","workflowStatus","workflowMovedAt","referencePositionId"};
        for (String key : keys) if (o.has(key)) details.put(key, o.opt(key));
        // Поле даты списания не должно случайно переводить обычный прибор в статус «Списан».
        String workflowStatus = details.optString("workflowStatus", "WAREHOUSE");
        if (!"WRITTEN_OFF".equals(workflowStatus)) details.put("writeOff", "");
        d.detailsJson = details.toString(); return d;
    }

    private static String emptyToNull(String s) { return s == null || s.trim().isEmpty() ? null : s; }

    private static JSONObject readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);

            // Логируем размер принятого JSON, если он не пустой (GET-запросы обычно пустые)
            if (bytes.length > 0) {
                System.out.printf("[HTTP IN ] %s %s | Получено от клиента: %d байт (JSON)%n", ex.getRequestMethod(), ex.getRequestURI().getPath(), bytes.length);
            }

            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        }
    }

    /** Обычный JSON-ответ без сжатия. */
    private static void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }

        System.out.printf("[HTTP OUT] %s %s | Отправлено: %d байт (без сжатия)%n", ex.getRequestMethod(), ex.getRequestURI().getPath(), bytes.length);
    }

    /**
     * JSON-ответ с gzip, если клиент прислал Accept-Encoding: gzip.
     * Используется для больших массивов (/api/devices, /api/devices/deleted, /api/audit).
     */
    private static void sendJsonGzip(HttpExchange ex, int code, Object body) throws IOException {
        byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
        boolean gzip = acceptsGzip(ex) && raw.length >= 1024;
        byte[] out = raw;
        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(raw); }
            out = bos.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }

        if (gzip) {
            long saved = raw.length - out.length;
            double ratio = 100.0 - ((double) out.length / raw.length * 100.0);
            System.out.printf("[HTTP OUT] %s %s | Исходный: %d байт | Отправлено (gzip): %d байт | Экономия: %d байт (%.1f%%)%n",
                    ex.getRequestMethod(), ex.getRequestURI().getPath(), raw.length, out.length, saved, ratio);
        } else {
            System.out.printf("[HTTP OUT] %s %s | Отправлено: %d байт (без сжатия)%n", ex.getRequestMethod(), ex.getRequestURI().getPath(), out.length);
        }
    }

    private static boolean acceptsGzip(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (header == null) return false;
        for (String part : header.split(",")) {
            String enc = part.trim().toLowerCase();
            int semi = enc.indexOf(';');
            String name = semi >= 0 ? enc.substring(0, semi).trim() : enc;
            if (name.equals("gzip") || name.equals("x-gzip")) {
                if (semi >= 0) {
                    String q = enc.substring(semi + 1).trim();
                    if (q.startsWith("q=")) {
                        try { if (Double.parseDouble(q.substring(2)) == 0d) return false; }
                        catch (NumberFormatException ignored) { }
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static void methodNotAllowed(HttpExchange ex) throws IOException {
        sendJson(ex, 405, new JSONObject().put("error", "method not allowed"));
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
