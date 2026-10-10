import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP controller for Stepwise. All personal API routes require a server-side session. */
public final class StepTrackerService {
    private static final String SESSION_COOKIE = "STEPWISE_SESSION";
    private static final long SESSION_SECONDS = 7L * 24 * 60 * 60;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final StepTrackerRepository repository;
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final PasswordHasher.Credentials dummyCredentials;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AuthRateLimiter authRateLimiter = new AuthRateLimiter();
    private final String androidAppOrigin = System.getenv().getOrDefault("ANDROID_APP_ORIGIN", "https://appassets.androidplatform.net");
    private final boolean secureCookies = Boolean.parseBoolean(System.getenv().getOrDefault("SESSION_COOKIE_SECURE", "true"));

    private StepTrackerService() throws SQLException, IOException {
        repository = new StepTrackerRepository();
        char[] dummyPassword = "not-a-real-account-password".toCharArray();
        dummyCredentials = passwordHasher.hash(dummyPassword);
        java.util.Arrays.fill(dummyPassword, '\0');
    }

    public static void main(String[] args) throws IOException {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("STEPTRACKER_PORT",
                    System.getenv().getOrDefault("PORT", "8080")));
            String bindHost = System.getenv().getOrDefault("STEPTRACKER_BIND_HOST", "127.0.0.1");
            StepTrackerService service = new StepTrackerService();
            HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            server.createContext("/", service::serveFrontend);
            server.createContext("/health", exchange -> service.handle(exchange, "GET", false, service::health));
            server.createContext("/api/auth/signup", exchange -> service.handle(exchange, "POST", false, service::signup));
            server.createContext("/api/auth/login", exchange -> service.handle(exchange, "POST", false, service::login));
            server.createContext("/api/auth/logout", exchange -> service.handle(exchange, "POST", false, service::logout));
            server.createContext("/api/auth/me", exchange -> service.handle(exchange, "GET", true, service::currentUser));
            server.createContext("/api/summary", exchange -> service.handle(exchange, "GET", true, service::summary));
            server.createContext("/api/steps", exchange -> service.handle(exchange, "POST", true, service::addSteps));
            server.createContext("/api/goal", exchange -> service.handle(exchange, "PUT", true, service::setGoal));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("Stepwise API listening on http://" + bindHost + ":" + port +
                    " (PostgreSQL; HTTPS is required outside local development)");
        } catch (SQLException e) {
            throw new IOException("Could not initialize PostgreSQL. Create the 'steptracker' database and configure DATABASE_URL, DATABASE_USER, and DATABASE_PASSWORD.", e);
        }
    }

    private void addSecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.set("Permissions-Policy", "geolocation=(self)");
    }

    private void serveFrontend(HttpExchange exchange) throws IOException {
        addSecurityHeaders(exchange);
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
            exchange.close();
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath == null || requestPath.equals("/")) requestPath = "/index.html";
        if (requestPath.contains("..") || requestPath.contains("\\")) {
            sendText(exchange, 400, "Invalid path", "text/plain; charset=utf-8");
            exchange.close();
            return;
        }
        String resource = "/web" + requestPath;
        try (var input = StepTrackerService.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }
            byte[] bytes = input.readAllBytes();
            String contentType = switch (requestPath.substring(requestPath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
                case "html" -> "text/html; charset=utf-8";
                case "css" -> "text/css; charset=utf-8";
                case "js" -> "text/javascript; charset=utf-8";
                case "svg" -> "image/svg+xml";
                case "png" -> "image/png";
                default -> "application/octet-stream";
            };
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("Cache-Control", "no-cache");
            headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self' https://unpkg.com; " +
                    "style-src 'self' https://unpkg.com 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org; " +
                    "connect-src 'self' https://tile.openstreetmap.org; font-src 'self' data:; object-src 'none'; " +
                    "base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
            exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : bytes.length);
            if (method.equals("GET")) exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private void handle(HttpExchange exchange, String method, boolean requiresSession, Handler handler) throws IOException {
        addSecurityHeaders(exchange);
        try {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals(exchange.getHttpContext().getPath())) {
                send(exchange, 404, error("not_found", "Route not found"));
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            boolean sameOrigin = isSameOrigin(origin, exchange.getRequestHeaders().getFirst("Host"));
            boolean androidOrigin = isAndroidOrigin(exchange);
            if (androidOrigin) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Access-Control-Allow-Origin", androidAppOrigin);
                headers.set("Access-Control-Allow-Credentials", "true");
                headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
                headers.set("Access-Control-Allow-Headers", "Content-Type");
                headers.set("Access-Control-Max-Age", "600");
                headers.set("Vary", "Origin");
            }
            if (exchange.getRequestMethod().equals("OPTIONS")) {
                if (!sameOrigin && !androidOrigin) {
                    send(exchange, 403, error("origin_denied", "Request origin is not allowed"));
                    return;
                }
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.getResponseHeaders().set("Allow", method);
                send(exchange, 405, error("method_not_allowed", "Method not allowed"));
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (!method.equals("GET") && (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))) {
                send(exchange, 415, error("unsupported_media_type", "Content-Type must be application/json"));
                return;
            }
            if (!method.equals("GET") && origin != null && !sameOrigin && !androidOrigin) {
                send(exchange, 403, error("origin_denied", "Request origin is not allowed"));
                return;
            }
            if (!method.equals("GET") && origin == null && readCookie(exchange) != null) {
                send(exchange, 403, error("origin_required", "Origin header is required for authenticated changes"));
                return;
            }
            if (androidOrigin && !secureCookies && path.startsWith("/api/auth/")) {
                send(exchange, 503, error("https_required", "Android sign-in requires an HTTPS API server"));
                return;
            }
            StepTrackerRepository.AuthUser user = null;
            if (requiresSession) {
                String token = readCookie(exchange);
                user = token == null ? null : repository.findSession(hashToken(token));
                if (user == null) {
                    clearSessionCookie(exchange, androidOrigin);
                    send(exchange, 401, error("unauthorized", "Sign in to continue"));
                    return;
                }
            }
            String body = handler.run(exchange, user);
            int status = path.equals("/api/auth/signup") || path.equals("/api/steps") ? 201 : 200;
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            send(exchange, status, body);
        } catch (TooManyRequests exception) {
            exchange.getResponseHeaders().set("Retry-After", "900");
            send(exchange, 429, error("rate_limited", "Too many attempts. Wait a few minutes and try again."));
        } catch (InvalidCredentials exception) {
            send(exchange, 401, error("invalid_credentials", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, error("invalid_request", exception.getMessage()));
        } catch (AccountAlreadyExists exception) {
            send(exchange, 400, error("account_unavailable", "Unable to create an account with those details. Try signing in if you already registered."));
        } catch (ArithmeticException exception) {
            send(exchange, 422, error("step_limit_exceeded", "Daily step total is too large"));
        } catch (SQLException exception) {
            send(exchange, 503, error("database_unavailable", "The database could not complete this request"));
        } catch (Exception exception) {
            send(exchange, 500, error("internal_error", "Unable to process request"));
        } finally {
            exchange.close();
        }
    }

    private String health(HttpExchange ignored, StepTrackerRepository.AuthUser ignoredUser) throws SQLException {
        repository.checkConnection();
        return "{\"status\":\"UP\",\"service\":\"stepwise-api\",\"database\":\"postgresql\"}";
    }

    private String signup(HttpExchange exchange, StepTrackerRepository.AuthUser ignored) throws Exception {
        authRateLimiter.check(exchange, "signup", 8, 60 * 60);
        Map<String, String> input = readStringObject(exchange);
        String email = normalizeEmail(input.get("email"));
        char[] password = requiredPassword(input.get("password"), true);
        try {
            PasswordHasher.Credentials credentials = passwordHasher.hash(password);
            StepTrackerRepository.AuthUser user;
            try {
                user = repository.createUser(email, credentials.salt(), credentials.hash(), credentials.iterations());
            } catch (SQLException exception) {
                if ("23505".equals(exception.getSQLState())) throw new AccountAlreadyExists();
                throw exception;
            }
            issueSession(exchange, user, isAndroidOrigin(exchange));
            return userJson(user);
        } finally { java.util.Arrays.fill(password, '\0'); }
    }

    private String login(HttpExchange exchange, StepTrackerRepository.AuthUser ignored) throws Exception {
        authRateLimiter.check(exchange, "login", 10, 15 * 60);
        Map<String, String> input = readStringObject(exchange);
        String email = normalizeEmail(input.get("email"));
        char[] password = requiredPassword(input.get("password"), false);
        try {
            StepTrackerRepository.AuthUser user = repository.findUser(email);
            boolean valid = user == null
                    ? passwordHasher.verify(password, dummyCredentials.salt(), dummyCredentials.hash(), dummyCredentials.iterations())
                    : passwordHasher.verify(password, user.salt(), user.hash(), user.iterations());
            if (!valid || user == null) throw new InvalidCredentials();
            issueSession(exchange, user, isAndroidOrigin(exchange));
            return userJson(user);
        } finally { java.util.Arrays.fill(password, '\0'); }
    }

    private String logout(HttpExchange exchange, StepTrackerRepository.AuthUser ignored) throws SQLException {
        String token = readCookie(exchange);
        if (token != null) repository.deleteSession(hashToken(token));
        clearSessionCookie(exchange, isAndroidOrigin(exchange));
        return "{}";
    }

    private String currentUser(HttpExchange ignored, StepTrackerRepository.AuthUser user) {
        return userJson(user);
    }

    private String summary(HttpExchange ignored, StepTrackerRepository.AuthUser user) throws SQLException {
        return toJson(repository.getSummary(user.id()));
    }

    private String addSteps(HttpExchange exchange, StepTrackerRepository.AuthUser user) throws Exception {
        StepInput input = stepInput(exchange);
        if (input.steps() < 1 || input.steps() > 1_000_000) {
            throw new IllegalArgumentException("steps must be between 1 and 1000000");
        }
        repository.addSteps(user.id(), input.steps(), input.activity());
        return toJson(repository.getSummary(user.id()));
    }

    private String setGoal(HttpExchange exchange, StepTrackerRepository.AuthUser user) throws Exception {
        long goal = number(exchange, "goal");
        if (goal < 100 || goal > 100_000) throw new IllegalArgumentException("goal must be between 100 and 100000");
        repository.setGoal(user.id(), (int) goal);
        return toJson(repository.getSummary(user.id()));
    }

    private void issueSession(HttpExchange exchange, StepTrackerRepository.AuthUser user, boolean androidOrigin) throws SQLException {
        repository.deleteExpiredSessions();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.createSession(hashToken(token), user.id(), Instant.now().plusSeconds(SESSION_SECONDS));
        String sameSite = androidOrigin ? "None" : "Strict";
        String secure = secureCookies ? "; Secure" : "";
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token +
                "; Path=/; Max-Age=" + SESSION_SECONDS + "; HttpOnly; SameSite=" + sameSite + secure);
    }

    private void clearSessionCookie(HttpExchange exchange, boolean androidOrigin) {
        String sameSite = androidOrigin ? "None" : "Strict";
        String secure = secureCookies ? "; Secure" : "";
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE +
                "=; Path=/; Max-Age=0; HttpOnly; SameSite=" + sameSite + secure);
    }

    private boolean isAndroidOrigin(HttpExchange exchange) {
        return androidAppOrigin.equals(exchange.getRequestHeaders().getFirst("Origin"));
    }

    private static String readCookie(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String cookie : cookieHeader.split(";")) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(SESSION_COOKIE)) {
                String value = pair[1];
                return value.matches("[A-Za-z0-9_-]{43}") ? value : null;
            }
        }
        return null;
    }

    private static String hashToken(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) throw new IllegalArgumentException("Enter your email address");
        String email = raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) throw new IllegalArgumentException("Enter a valid email address");
        return email;
    }

    private static char[] requiredPassword(String password, boolean signup) {
        if (password == null) throw new IllegalArgumentException("Enter your password");
        int length = password.codePointCount(0, password.length());
        if (signup && length < 12) throw new IllegalArgumentException("Use a password with at least 12 characters");
        if (length == 0 || length > 128) throw new IllegalArgumentException("Password must be at most 128 characters");
        return password.toCharArray();
    }

    private static boolean isSameOrigin(String origin, String hostHeader) {
        if (origin == null || hostHeader == null) return false;
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getRawAuthority() != null
                    && uri.getRawAuthority().equalsIgnoreCase(hostHeader) && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException exception) { return false; }
    }

    private static String userJson(StepTrackerRepository.AuthUser user) {
        return "{\"id\":" + user.id() + ",\"email\":\"" + jsonEscape(user.email()) + "\"}";
    }

    private static String toJson(StepTrackerRepository.TrackerSummary summary) {
        StringBuilder json = new StringBuilder("{\"date\":\"").append(summary.date()).append("\",\"steps\":")
                .append(summary.steps()).append(",\"goal\":").append(summary.goal()).append(",\"streak\":")
                .append(summary.streak()).append(",\"history\":[");
        for (int i = 0; i < summary.history().size(); i++) {
            if (i > 0) json.append(',');
            StepTrackerRepository.DailySteps day = summary.history().get(i);
            json.append("{\"date\":\"").append(day.date()).append("\",\"steps\":").append(day.steps()).append('}');
        }
        return json.append("]}").toString();
    }

    private static long number(HttpExchange exchange, String field) throws IOException {
        String body = requestBody(exchange);
        Matcher matcher = Pattern.compile("\\s*\\{\\s*\\\"" + field + "\\\"\\s*:\\s*(\\d+)\\s*}\\s*").matcher(body);
        if (!matcher.matches()) throw new IllegalArgumentException("request must be a JSON object containing numeric field '" + field + "'");
        try { return Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid " + field); }
    }

    private static StepInput stepInput(HttpExchange exchange) throws IOException {
        String body = requestBody(exchange);
        Matcher matcher = Pattern.compile("\\s*\\{\\s*\\\"steps\\\"\\s*:\\s*(\\d+)\\s*(?:,\\s*\\\"activity\\\"\\s*:\\s*\\\"(walking|running|cycling)\\\"\\s*)?}\\s*").matcher(body);
        if (!matcher.matches()) throw new IllegalArgumentException("request must include steps and an optional walking, running, or cycling activity");
        try { return new StepInput(Long.parseLong(matcher.group(1)), matcher.group(2)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid steps"); }
    }

    private static Map<String, String> readStringObject(HttpExchange exchange) throws IOException {
        String body = requestBody(exchange);
        JsonStringObject parser = new JsonStringObject(body);
        Map<String, String> fields = parser.parse();
        if (!fields.keySet().equals(java.util.Set.of("email", "password"))) {
            throw new IllegalArgumentException("request must contain only email and password fields");
        }
        return fields;
    }

    private static String requestBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new IllegalArgumentException("request body is too large");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String error(String code, String message) {
        return "{\"error\":{\"code\":\"" + jsonEscape(code) + "\",\"message\":\"" + jsonEscape(message) + "\"}}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private final class AuthRateLimiter {
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
        private void check(HttpExchange exchange, String action, int limit, long intervalSeconds) {
            String address = exchange.getRemoteAddress().getAddress().getHostAddress();
            String key = action + ":" + address;
            long now = System.nanoTime();
            Window window = windows.compute(key, (ignored, previous) -> previous == null || now - previous.startedAt > intervalSeconds * 1_000_000_000L
                    ? new Window(now, 1) : new Window(previous.startedAt, previous.count + 1));
            if (window.count > limit) throw new TooManyRequests();
            if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > 3_600_000_000_000L);
        }
        private record Window(long startedAt, int count) {}
    }

    private static final class JsonStringObject {
        private final String value;
        private int cursor;
        private JsonStringObject(String value) { this.value = value; }
        private Map<String, String> parse() {
            HashMap<String, String> result = new HashMap<>();
            whitespace(); expect('{'); whitespace();
            if (peek('}')) { cursor++; finish(); return result; }
            while (true) {
                whitespace(); String key = string(); whitespace(); expect(':'); whitespace(); String fieldValue = string();
                if (result.putIfAbsent(key, fieldValue) != null) throw invalid();
                whitespace(); if (peek('}')) { cursor++; break; } expect(',');
            }
            finish(); return result;
        }
        private String string() {
            expect('"'); StringBuilder output = new StringBuilder();
            while (cursor < value.length()) {
                char ch = value.charAt(cursor++);
                if (ch == '"') return output.toString();
                if (ch < 0x20) throw invalid();
                if (ch != '\\') { output.append(ch); continue; }
                if (cursor >= value.length()) throw invalid();
                char escape = value.charAt(cursor++);
                switch (escape) {
                    case '"', '\\', '/' -> output.append(escape);
                    case 'b' -> output.append('\b'); case 'f' -> output.append('\f'); case 'n' -> output.append('\n');
                    case 'r' -> output.append('\r'); case 't' -> output.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > value.length()) throw invalid();
                        try { output.append((char) Integer.parseInt(value.substring(cursor, cursor + 4), 16)); }
                        catch (NumberFormatException exception) { throw invalid(); }
                        cursor += 4;
                    }
                    default -> throw invalid();
                }
            }
            throw invalid();
        }
        private void whitespace() { while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++; }
        private void expect(char expected) { if (!peek(expected)) throw invalid(); cursor++; }
        private boolean peek(char expected) { return cursor < value.length() && value.charAt(cursor) == expected; }
        private void finish() { whitespace(); if (cursor != value.length()) throw invalid(); }
        private IllegalArgumentException invalid() { return new IllegalArgumentException("request body must be a JSON object of string fields"); }
    }

    private static final class InvalidCredentials extends IllegalArgumentException {
        private InvalidCredentials() { super("Invalid email or password"); }
    }
    private static final class AccountAlreadyExists extends RuntimeException {}
    private static final class TooManyRequests extends RuntimeException {}
    @FunctionalInterface private interface Handler { String run(HttpExchange exchange, StepTrackerRepository.AuthUser user) throws Exception; }
    private record StepInput(long steps, String activity) {}
}
