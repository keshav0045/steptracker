import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Owns PostgreSQL schema setup, account/session persistence, and user-scoped queries. */
public final class StepTrackerRepository {
    private final String databaseUrl = System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/steptracker");
    private final String jdbcUrl = toJdbcUrl(databaseUrl);
    private final String dbUser = databaseUser(databaseUrl);
    private final String dbPassword = databasePassword(databaseUrl);
    private final Path legacyFile = Path.of(System.getProperty("user.home"), ".steptracker", "data.properties");

    public StepTrackerRepository() throws SQLException, IOException {
        initializeSchema();
        migrateLegacyFile();
    }

    public void checkConnection() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
    }

    public AuthUser findUser(String email) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, email, password_salt, password_hash, password_iterations FROM app_users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new AuthUser(result.getLong("id"), result.getString("email"),
                        result.getBytes("password_salt"), result.getBytes("password_hash"), result.getInt("password_iterations")) : null;
            }
        }
    }

    public AuthUser createUser(String email, byte[] salt, byte[] hash, int iterations) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO app_users (email, password_salt, password_hash, password_iterations) VALUES (?, ?, ?, ?) RETURNING id")) {
            statement.setString(1, email);
            statement.setBytes(2, salt);
            statement.setBytes(3, hash);
            statement.setInt(4, iterations);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new AuthUser(result.getLong(1), email, salt, hash, iterations);
            }
        }
    }

    public void createSession(String tokenHash, long userId, Instant expiresAt) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
            statement.setString(1, tokenHash);
            statement.setLong(2, userId);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.executeUpdate();
        }
    }

    public AuthUser findSession(String tokenHash) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT u.id, u.email, u.password_salt, u.password_hash, u.password_iterations " +
                        "FROM auth_sessions s JOIN app_users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > NOW()")) {
            statement.setString(1, tokenHash);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new AuthUser(result.getLong("id"), result.getString("email"),
                        result.getBytes("password_salt"), result.getBytes("password_hash"), result.getInt("password_iterations")) : null;
            }
        }
    }

    public void deleteSession(String tokenHash) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auth_sessions WHERE token_hash = ?")) {
            statement.setString(1, tokenHash);
            statement.executeUpdate();
        }
    }

    public void deleteExpiredSessions() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM auth_sessions WHERE expires_at <= NOW()");
        }
    }

    public TrackerSummary getSummary(long userId) throws SQLException {
        LocalDate today = LocalDate.now();
        try (Connection connection = connection()) {
            ensureSettings(connection, userId);
            long todaySteps = steps(connection, userId, today);
            long goal = goal(connection, userId);
            int streak = streak(connection, userId, today, goal);
            List<DailySteps> history = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate date = today.minusDays(i);
                history.add(new DailySteps(date, steps(connection, userId, date)));
            }
            return new TrackerSummary(today, todaySteps, goal, streak, List.copyOf(history));
        }
    }

    public void addSteps(long userId, long amount, String activity) throws SQLException {
        LocalDate today = LocalDate.now();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO daily_steps (user_id, step_date, steps) VALUES (?, ?, ?) " +
                                "ON CONFLICT (user_id, step_date) WHERE user_id IS NOT NULL " +
                                "DO UPDATE SET steps = daily_steps.steps + EXCLUDED.steps RETURNING steps")) {
                    statement.setLong(1, userId);
                    statement.setObject(2, today);
                    statement.setLong(3, amount);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw new SQLException("Step total was not returned by PostgreSQL");
                    }
                }
                if (activity != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO step_activity_logs (user_id, activity_type, steps) VALUES (?, ?, ?)")) {
                        statement.setLong(1, userId);
                        statement.setString(2, activity);
                        statement.setLong(3, amount);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sqlException) throw sqlException;
                throw new SQLException("Could not save step activity", e);
            }
        }
    }

    public void setGoal(long userId, int goal) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tracker_settings (user_id, daily_goal) VALUES (?, ?) " +
                        "ON CONFLICT (user_id) DO UPDATE SET daily_goal = EXCLUDED.daily_goal")) {
            statement.setLong(1, userId);
            statement.setInt(2, goal);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }

    private static String toJdbcUrl(String raw) {
        if (raw.startsWith("jdbc:postgresql:")) return raw;
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL JDBC or PostgreSQL URL");
        }
        URI uri = URI.create(raw);
        String authority = uri.getRawAuthority();
        int credentialsSeparator = authority.lastIndexOf('@');
        if (credentialsSeparator >= 0) authority = authority.substring(credentialsSeparator + 1);
        String query = uri.getRawQuery();
        if (query != null) query = query.replace("channel_binding=", "channelBinding=");
        return "jdbc:postgresql://" + authority + uri.getRawPath() + (query == null ? "" : "?" + query);
    }

    private static String databaseUser(String raw) {
        String configured = System.getenv("DATABASE_USER");
        if (configured != null && !configured.isBlank()) return configured;
        String uriUser = uriCredential(raw, true);
        return uriUser != null ? uriUser : System.getenv().getOrDefault("PGUSER", System.getProperty("user.name"));
    }

    private static String databasePassword(String raw) {
        String configured = System.getenv("DATABASE_PASSWORD");
        if (configured != null && !configured.isBlank()) return configured;
        String uriPassword = uriCredential(raw, false);
        return uriPassword == null ? "" : uriPassword;
    }

    private static String uriCredential(String raw, boolean username) {
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) return null;
        String userInfo = URI.create(raw).getRawUserInfo();
        if (userInfo == null) return null;
        int separator = userInfo.indexOf(':');
        String value = username ? (separator < 0 ? userInfo : userInfo.substring(0, separator))
                : (separator < 0 ? "" : userInfo.substring(separator + 1));
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            statement.execute("CREATE TABLE IF NOT EXISTS app_users (id BIGSERIAL PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_salt BYTEA NOT NULL, password_hash BYTEA NOT NULL, password_iterations INTEGER NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            statement.execute("CREATE TABLE IF NOT EXISTS auth_sessions (token_hash CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS auth_sessions_user_id_idx ON auth_sessions(user_id)");

            // Create the pre-account schema if this is a new database, then migrate without assigning legacy data to a user.
            statement.execute("CREATE TABLE IF NOT EXISTS daily_steps (step_date DATE PRIMARY KEY, steps BIGINT NOT NULL CHECK (steps >= 0))");
            statement.execute("CREATE TABLE IF NOT EXISTS step_activity_logs (id BIGSERIAL PRIMARY KEY, activity_type TEXT NOT NULL CHECK (activity_type IN ('walking', 'running', 'cycling')), steps BIGINT NOT NULL CHECK (steps > 0), logged_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            if (tableExists(connection, "tracker_settings") && columnExists(connection, "tracker_settings", "id")
                    && !tableExists(connection, "tracker_settings_legacy")) {
                statement.execute("ALTER TABLE tracker_settings RENAME CONSTRAINT tracker_settings_pkey TO tracker_settings_legacy_pkey");
                statement.execute("ALTER TABLE tracker_settings RENAME TO tracker_settings_legacy");
            }
            statement.execute("CREATE TABLE IF NOT EXISTS tracker_settings (user_id BIGINT PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE, daily_goal INTEGER NOT NULL CHECK (daily_goal BETWEEN 100 AND 100000))");

            if (!columnExists(connection, "daily_steps", "user_id")) {
                statement.execute("ALTER TABLE daily_steps ADD COLUMN user_id BIGINT REFERENCES app_users(id) ON DELETE CASCADE");
            }
            statement.execute("ALTER TABLE daily_steps DROP CONSTRAINT IF EXISTS daily_steps_pkey");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS daily_steps_user_date_idx ON daily_steps(user_id, step_date) WHERE user_id IS NOT NULL");
            if (!columnExists(connection, "step_activity_logs", "user_id")) {
                statement.execute("ALTER TABLE step_activity_logs ADD COLUMN user_id BIGINT REFERENCES app_users(id) ON DELETE CASCADE");
            }
            statement.execute("CREATE INDEX IF NOT EXISTS step_activity_logs_user_time_idx ON step_activity_logs(user_id, logged_at DESC) WHERE user_id IS NOT NULL");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (2) ON CONFLICT DO NOTHING");
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?)")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?)")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private void migrateLegacyFile() throws SQLException, IOException {
        try (Connection connection = connection(); PreparedStatement check = connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = 1");
             ResultSet result = check.executeQuery()) {
            if (result.next()) return;
        }
        Properties legacy = new Properties();
        if (Files.exists(legacyFile)) try (InputStream input = Files.newInputStream(legacyFile)) { legacy.load(input); }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                // Legacy single-user rows stay ownerless and are never exposed through authenticated endpoints.
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO daily_steps (user_id, step_date, steps) VALUES (NULL, ?, ?)")) {
                    for (String property : legacy.stringPropertyNames()) {
                        if (!property.startsWith("steps.")) continue;
                        try {
                            LocalDate date = LocalDate.parse(property.substring("steps.".length()));
                            long count = Long.parseLong(legacy.getProperty(property));
                            if (count < 0) continue;
                            insert.setObject(1, date);
                            insert.setLong(2, count);
                            insert.addBatch();
                        } catch (RuntimeException ignored) { }
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement mark = connection.prepareStatement("INSERT INTO schema_migrations (version) VALUES (1) ON CONFLICT DO NOTHING")) {
                    mark.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sqlException) throw sqlException;
                if (e instanceof IOException ioException) throw ioException;
                throw new SQLException("Could not import legacy step data", e);
            }
        }
    }

    private static void ensureSettings(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tracker_settings (user_id, daily_goal) VALUES (?, 8000) ON CONFLICT (user_id) DO NOTHING")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    private static long steps(Connection connection, long userId, LocalDate date) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT steps FROM daily_steps WHERE user_id = ? AND step_date = ?")) {
            statement.setLong(1, userId);
            statement.setObject(2, date);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
        }
    }

    private static long goal(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT daily_goal FROM tracker_settings WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing user tracker settings row");
                return result.getLong(1);
            }
        }
    }

    private static int streak(Connection connection, long userId, LocalDate date, long goal) throws SQLException {
        int days = 0;
        while (steps(connection, userId, date.minusDays(days)) >= goal) days++;
        return days;
    }

    public record AuthUser(long id, String email, byte[] salt, byte[] hash, int iterations) {}
    public record DailySteps(LocalDate date, long steps) {}
    public record TrackerSummary(LocalDate date, long steps, long goal, int streak, List<DailySteps> history) {}
}
