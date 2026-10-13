package cn.xjn.xim.storage;

import java.nio.file.Path;
import java.util.Locale;

/** JVM properties override environment variables. Never log credentials or connection URLs. */
public record DatabaseConfig(String type, String url, String username, String password) {
    public static DatabaseConfig fromEnvironment() {
        String type = value("xim.db.type", "XIM_DB_TYPE", "sqlite").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "sqlite" -> sqlite(Path.of(value("xim.db.sqlite.path", "XIM_DB_SQLITE_PATH", "data/xim.db")));
            case "pgsql", "postgresql" -> new DatabaseConfig("pgsql",
                    value("xim.db.url", "XIM_DB_URL", "jdbc:postgresql://localhost:5432/xim"),
                    value("xim.db.user", "XIM_DB_USER", ""), value("xim.db.password", "XIM_DB_PASSWORD", ""));
            default -> throw new IllegalArgumentException("xim.db.type must be sqlite or pgsql");
        };
    }
    public static DatabaseConfig sqlite(Path path) {
        return new DatabaseConfig("sqlite", "jdbc:sqlite:" + path.toAbsolutePath(), "", "");
    }
    private static String value(String property, String env, String fallback) {
        return System.getProperty(property, System.getenv().getOrDefault(env, fallback));
    }
    @Override public String toString() { return "DatabaseConfig[type=" + type + "]"; }
}
