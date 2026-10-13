package cn.xjn.xim.integration;

import cn.xjn.xim.storage.*;
import org.junit.Test;
import org.junit.Assume;
import java.sql.DriverManager;

/** Never touches existing tables: each run creates and drops its own random schema. */
public class PostgreSQLStoreIT {
    @Test public void postgresRealDatabaseContract() throws Exception {
        String url = System.getenv("XIM_TEST_PG_URL");
        Assume.assumeTrue("PostgreSQL not configured: set XIM_TEST_PG_URL", url != null && !url.isBlank());
        String user = System.getenv().getOrDefault("XIM_TEST_PG_USER", "");
        String password = System.getenv().getOrDefault("XIM_TEST_PG_PASSWORD", "");
        String schema = "xim_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url,user,password); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                var config = new DatabaseConfig("pgsql", url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user,password);
                ChatStoreTest.recoveryContract(config);
                MessageRetryStoreTest.retryContract(config);
                AccountStoreTest.accountContract(config);
                ConversationReadStoreTest.readContract(config);
                SessionStoreTest.sessionContract(config);
                ContactStoreTest.directoryContract(config);
            } finally { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
