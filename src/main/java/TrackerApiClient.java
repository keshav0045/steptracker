import java.io.IOException;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP boundary between the desktop UI and step-tracker service. */
public final class TrackerApiClient {
    private static final String BASE_URL = System.getenv().getOrDefault("STEPTRACKER_API", "http://localhost:8080").replaceAll("/+$", "");
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();

    public Account currentUser() throws IOException, InterruptedException {
        String json = request("GET", "/api/auth/me", null);
        return new Account(numberField(json, "id"), stringField(json, "email"));
    }
    public Account signIn(String email, String password) throws IOException, InterruptedException {
        return authRequest("/api/auth/login", email, password);
    }
    public Account signUp(String email, String password) throws IOException, InterruptedException {
        return authRequest("/api/auth/signup", email, password);
    }
    public void signOut() throws IOException, InterruptedException {
        request("POST", "/api/auth/logout", "{}");
    }

    public Snapshot summary() throws IOException, InterruptedException {
        return parse(request("GET", "/api/summary", null));
    }
    public Snapshot addSteps(long amount) throws IOException, InterruptedException {
        return parse(request("POST", "/api/steps", "{\"steps\":" + amount + "}"));
    }
    public Snapshot addSteps(long amount, String activity) throws IOException, InterruptedException {
        return parse(request("POST", "/api/steps", "{\"steps\":" + amount + ",\"activity\":\"" + activity + "\"}"));
    }
    public Snapshot setGoal(long goal) throws IOException, InterruptedException {
        return parse(request("PUT", "/api/goal", "{\"goal\":" + goal + "}"));
    }

    private String request(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json");
        if (body == null) builder.GET();
        else {
            URI base = URI.create(BASE_URL);
            String origin = base.getScheme() + "://" + base.getRawAuthority();
            builder.header("Origin", origin).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new ApiException(response.statusCode(), errorMessage(response.body()));
        return response.body();
    }

    private Account authRequest(String path, String email, String password) throws IOException, InterruptedException {
        String body = "{\"email\":" + jsonQuote(email) + ",\"password\":" + jsonQuote(password) + "}";
        String json = request("POST", path, body);
        return new Account(numberField(json, "id"), stringField(json, "email"));
    }

    private static String jsonQuote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> { if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c)); else escaped.append(c); }
            }
        }
        return escaped.append('"').toString();
    }

    private static String errorMessage(String json) {
        Matcher matcher = Pattern.compile("\\\"message\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\n", "\n") : "Request failed";
    }

    private static Snapshot parse(String json) throws IOException {
        try {
            LocalDate date = LocalDate.parse(stringField(json, "date"));
            long steps = numberField(json, "steps");
            long goal = numberField(json, "goal");
            int streak = Math.toIntExact(numberField(json, "streak"));
            Matcher matcher = Pattern.compile("\\{\\\"date\\\":\\\"(\\d{4}-\\d{2}-\\d{2})\\\",\\\"steps\\\":(\\d+)\\}").matcher(json);
            List<Day> days = new ArrayList<>();
            while (matcher.find()) days.add(new Day(LocalDate.parse(matcher.group(1)), Long.parseLong(matcher.group(2))));
            return new Snapshot(date, steps, goal, streak, days);
        } catch (RuntimeException e) {
            throw new IOException("The step-tracker API returned an invalid response", e);
        }
    }
    private static String stringField(String json, String field) {
        Matcher m = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing " + field);
        return m.group(1);
    }
    private static long numberField(String json, String field) {
        Matcher m = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing " + field);
        return Long.parseLong(m.group(1));
    }

    public record Day(LocalDate date, long steps) {}
    public record Account(long id, String email) {}
    public record Snapshot(LocalDate date, long steps, long goal, int streak, List<Day> history) {}
    public static final class ApiException extends IOException {
        private final int statusCode;
        public ApiException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
        public int statusCode() { return statusCode; }
    }
}
