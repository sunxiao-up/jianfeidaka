import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 减肥打卡后端：使用 JDK 内置 HttpServer，零第三方依赖，数据持久化到 data.json。
 * 启动：java WeightTrackerServer（需在项目根目录运行）
 */
public final class WeightTrackerServer {

    private static final int PORT = 3000;
    private static final Path DATA_FILE = Paths.get("data.json");
    private static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath().normalize();

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DELETE_PATTERN = Pattern.compile("^/api/records/([\\w-]+)$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\w\\u4e00-\\u9fa5-]{2,20}$");

    // 会话保存在内存中，重启服务后需要重新登录
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put(".html", "text/html; charset=utf-8");
        MIME.put(".css", "text/css; charset=utf-8");
        MIME.put(".js", "text/javascript; charset=utf-8");
        MIME.put(".json", "application/json; charset=utf-8");
        MIME.put(".png", "image/png");
        MIME.put(".jpg", "image/jpeg");
        MIME.put(".svg", "image/svg+xml");
        MIME.put(".ico", "image/x-icon");
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", WeightTrackerServer::handle);
        server.setExecutor(null);
        server.start();
        System.out.println("减肥打卡已启动: http://localhost:" + PORT);
    }

    private static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
            } else if ("GET".equals(ex.getRequestMethod())) {
                serveStatic(ex, path);
            } else {
                sendJson(ex, 405, mapOf("error", "Method Not Allowed"));
            }
        } catch (Exception e) {
            try {
                String msg = e.getMessage() == null ? "服务器内部错误" : e.getMessage();
                sendJson(ex, 500, mapOf("error", msg));
            } catch (IOException ignored) {
                // 响应已无法写出时忽略
            }
        } finally {
            ex.close();
        }
    }

    // ---------- API ----------

    @SuppressWarnings("unchecked")
    private static void handleApi(HttpExchange ex, String path) throws IOException {
        Map<String, Object> data = loadData();
        String method = ex.getRequestMethod();

        // POST /api/register
        if ("POST".equals(method) && "/api/register".equals(path)) {
            Map<String, Object> body = readObject(ex);
            String username = body.get("username") == null ? "" : String.valueOf(body.get("username")).trim();
            String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));

            if (!USERNAME_PATTERN.matcher(username).matches()) {
                sendJson(ex, 400, mapOf("error", "用户名需为 2~20 位字母、数字、中文、- 或 _"));
                return;
            }
            if (password.length() < 6) {
                sendJson(ex, 400, mapOf("error", "密码至少 6 位"));
                return;
            }

            List<Object> users = usersOf(data);
            if (findUser(users, username) != null) {
                sendJson(ex, 409, mapOf("error", "用户名已存在"));
                return;
            }

            String salt = randomHex(16);
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("username", username);
            user.put("salt", salt);
            user.put("passwordHash", hashPassword(salt, password));
            // 迁移旧版未登录时代的单用户数据到首个注册用户
            Object legacyProfile = data.remove("profile");
            Object legacyRecords = data.remove("records");
            user.put("profile", legacyProfile != null ? legacyProfile : new LinkedHashMap<String, Object>());
            user.put("records", legacyRecords != null ? legacyRecords : new ArrayList<Object>());
            users.add(user);
            saveData(data);

            setSession(ex, username);
            sendJson(ex, 200, mapOf("username", username));
            return;
        }

        // POST /api/login
        if ("POST".equals(method) && "/api/login".equals(path)) {
            Map<String, Object> body = readObject(ex);
            String username = body.get("username") == null ? "" : String.valueOf(body.get("username")).trim();
            String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));

            Map<String, Object> user = findUser(usersOf(data), username);
            if (user == null
                    || !hashPassword(String.valueOf(user.get("salt")), password)
                            .equals(String.valueOf(user.get("passwordHash")))) {
                sendJson(ex, 401, mapOf("error", "用户名或密码错误"));
                return;
            }
            setSession(ex, username);
            sendJson(ex, 200, mapOf("username", username));
            return;
        }

        // POST /api/logout
        if ("POST".equals(method) && "/api/logout".equals(path)) {
            clearSession(ex);
            sendJson(ex, 200, mapOf("ok", true));
            return;
        }

        // GET /api/me
        if ("GET".equals(method) && "/api/me".equals(path)) {
            String username = currentUser(ex);
            if (username == null) {
                sendJson(ex, 401, mapOf("error", "未登录"));
            } else {
                sendJson(ex, 200, mapOf("username", username));
            }
            return;
        }

        // 以下接口均需登录
        String username = currentUser(ex);
        if (username == null) {
            sendJson(ex, 401, mapOf("error", "未登录"));
            return;
        }
        Map<String, Object> user = findUser(usersOf(data), username);
        if (user == null) {
            clearSession(ex);
            sendJson(ex, 401, mapOf("error", "账号不存在，请重新登录"));
            return;
        }

        // GET /api/records
        if ("GET".equals(method) && "/api/records".equals(path)) {
            List<Object> records = (List<Object>) user.get("records");
            List<Map<String, Object>> sorted = new ArrayList<>();
            for (Object r : records) {
                sorted.add((Map<String, Object>) r);
            }
            sorted.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
            sendJson(ex, 200, sorted);
            return;
        }

        // POST /api/records
        if ("POST".equals(method) && "/api/records".equals(path)) {
            Map<String, Object> body = readObject(ex);
            String date = String.valueOf(body.get("date"));
            double weight = asDouble(body.get("weight"));
            String note = body.get("note") == null ? "" : String.valueOf(body.get("note"));
            if (note.length() > 200) note = note.substring(0, 200);

            if (!DATE_PATTERN.matcher(date).matches()) {
                sendJson(ex, 400, mapOf("error", "日期格式不正确"));
                return;
            }
            if (!isValidWeight(weight)) {
                sendJson(ex, 400, mapOf("error", "体重需为 20~500 之间的数字"));
                return;
            }

            List<Object> records = (List<Object>) user.get("records");
            Map<String, Object> existing = null;
            for (Object r : records) {
                Map<String, Object> m = (Map<String, Object>) r;
                if (date.equals(String.valueOf(m.get("date")))) {
                    existing = m;
                    break;
                }
            }

            if (existing != null) {
                existing.put("weight", weight);
                existing.put("note", note);
            } else {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", genId());
                rec.put("date", date);
                rec.put("weight", weight);
                rec.put("note", note);
                records.add(rec);
            }
            saveData(data);
            sendJson(ex, 200, mapOf("ok", true));
            return;
        }

        // DELETE /api/records/{id}
        Matcher dm = DELETE_PATTERN.matcher(path);
        if ("DELETE".equals(method) && dm.matches()) {
            String id = dm.group(1);
            List<Object> records = (List<Object>) user.get("records");
            boolean removed = records.removeIf(r ->
                    id.equals(String.valueOf(((Map<String, Object>) r).get("id"))));
            if (!removed) {
                sendJson(ex, 404, mapOf("error", "记录不存在"));
                return;
            }
            saveData(data);
            sendJson(ex, 200, mapOf("ok", true));
            return;
        }

        // GET /api/profile
        if ("GET".equals(method) && "/api/profile".equals(path)) {
            sendJson(ex, 200, user.get("profile"));
            return;
        }

        // PUT /api/profile
        if ("PUT".equals(method) && "/api/profile".equals(path)) {
            Map<String, Object> body = readObject(ex);
            Double height = optionalDouble(body.get("height"));
            Double target = optionalDouble(body.get("targetWeight"));

            if (height != null && (height < 50 || height > 300)) {
                sendJson(ex, 400, mapOf("error", "身高需为 50~300 之间的数字"));
                return;
            }
            if (target != null && !isValidWeight(target)) {
                sendJson(ex, 400, mapOf("error", "目标体重需为 20~500 之间的数字"));
                return;
            }

            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("height", height);
            profile.put("targetWeight", target);
            user.put("profile", profile);
            saveData(data);
            sendJson(ex, 200, mapOf("ok", true));
            return;
        }

        sendJson(ex, 404, mapOf("error", "接口不存在"));
    }

    // ---------- 登录态与用户 ----------

    @SuppressWarnings("unchecked")
    private static List<Object> usersOf(Map<String, Object> data) {
        Object users = data.get("users");
        if (users instanceof List) {
            return (List<Object>) users;
        }
        List<Object> list = new ArrayList<>();
        data.put("users", list);
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findUser(List<Object> users, String username) {
        for (Object u : users) {
            Map<String, Object> m = (Map<String, Object>) u;
            if (username.equals(String.valueOf(m.get("username")))) {
                return m;
            }
        }
        return null;
    }

    private static String currentUser(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "sid".equals(kv[0])) {
                return SESSIONS.get(kv[1]);
            }
        }
        return null;
    }

    private static void setSession(HttpExchange ex, String username) {
        String token = randomHex(32);
        SESSIONS.put(token, username);
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private static void clearSession(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "sid".equals(kv[0])) {
                    SESSIONS.remove(kv[1]);
                }
            }
        }
        ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; HttpOnly; Max-Age=0");
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String hashPassword(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 静态文件 ----------

    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        String p = "/".equals(path) ? "/index.html" : path;
        String decoded = URLDecoder.decode(p, StandardCharsets.UTF_8);
        Path file = PUBLIC_DIR.resolve(decoded.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(PUBLIC_DIR)) {
            sendText(ex, 403, "Forbidden");
            return;
        }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            sendText(ex, 404, "Not Found");
            return;
        }
        byte[] content = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String mime = dot >= 0
                ? MIME.getOrDefault(name.substring(dot).toLowerCase(), "application/octet-stream")
                : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(content);
        }
    }

    // ---------- 数据存取 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadData() {
        if (Files.exists(DATA_FILE)) {
            try {
                Object parsed = JsonUtil.parse(new String(Files.readAllBytes(DATA_FILE), StandardCharsets.UTF_8));
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                }
            } catch (Exception ignored) {
                // 文件损坏则回退到默认空数据
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", new ArrayList<Object>());
        return data;
    }

    private static void saveData(Map<String, Object> data) throws IOException {
        Files.write(DATA_FILE, JsonUtil.stringify(data, true).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 工具 ----------

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] bytes = JsonUtil.stringify(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> readObject(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        Object parsed = JsonUtil.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) {
            throw new IOException("请求体需为 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    private static double asDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Double optionalDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return null;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isValidWeight(double w) {
        return Double.isFinite(w) && w >= 20 && w <= 500;
    }

    private static String genId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private WeightTrackerServer() {
    }
}
