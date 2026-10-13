package cn.xjn.xim.storage;

import org.junit.Test;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.Assert.*;

public class AccountStoreTest {
    public static void accountContract(DatabaseConfig config) {
        String username = "acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String id, peerId, group = UUID.randomUUID().toString();
        long version;
        try (var store = new ChatStore(config, false)) {
            var user = store.register(username, "初始密码 🌍123"); id = user.getUserId();
            var peer = store.register(username + "_b", "peer-password"); peerId = peer.getUserId();
            assertEquals(id, store.authenticate(username, "初始密码 🌍123").getUserId());
            assertNull(store.authenticate(username, "wrong-password"));
            assertNull(store.authenticate("missing_account", "wrong-password"));
            assertThrows(IllegalArgumentException.class, () -> store.register(username, "other-password"));
            store.createGroup(group, List.of(id, peerId));
            store.save(user, peerId, null, "private history"); store.save(user, null, group, "group history");
            version = store.credentialVersion(id);
            assertThrows(IllegalArgumentException.class, () -> store.changePassword(id, "wrong-password", "next-password"));
            assertEquals(version, store.credentialVersion(id));
            assertEquals(version + 1, store.changePassword(id, "初始密码 🌍123", "new-password"));
            assertNull(store.authenticate(username, "初始密码 🌍123"));
            assertEquals(id, store.authenticate(username, "new-password").getUserId());
        }
        try (var store = new ChatStore(config, false)) {
            assertEquals(id, store.authenticate(username, "new-password").getUserId());
            assertNull(store.authenticate(username, "初始密码 🌍123"));
            assertEquals(version + 1, store.credentialVersion(id));
            assertTrue(store.isMember(group, id));
            assertEquals("private history", store.history(id, "user", peerId, null, 50).messages().getFirst().getMessage());
            assertEquals("group history", store.history(id, "group", group, null, 50).messages().getFirst().getMessage());
        }
    }
    @Test public void createsAuthenticatesChangesAndRecoversAccountsWithHistory() throws Exception {
        withFile(AccountStoreTest::accountContract);
    }
    @Test public void rejectsInvalidAccountsAndPasswordsWithoutInserting() throws Exception {
        withFile(config -> {
            try (var store = new ChatStore(config, false)) {
                for (String username : Arrays.asList(null, "", "aa", " spaced ", "a'b", "用户", "x".repeat(33)))
                    assertThrows(IllegalArgumentException.class, () -> store.register(username, "valid-password"));
                for (String password : Arrays.asList(null, "", "short", "        ", "password\n", "x".repeat(129)))
                    assertThrows(IllegalArgumentException.class, () -> store.register("valid_user", password));
                try (var connection = DriverManager.getConnection(config.url()); var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT COUNT(*) FROM xim_users")) {
                    rs.next(); assertEquals(0, rs.getInt(1));
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertNotNull(store.register("valid_user", "valid-password"));
                assertNotNull(store.register("VALID_USER", "valid-password"));
            }
        });
    }
    @Test public void productionNeverCreatesOrAcceptsDemoPasswordsAndDevNeverResetsChangedPasswords() throws Exception {
        withFile(config -> {
            try (var store = new ChatStore(config, false)) { assertNull(store.authenticate("root", "root")); }
            String id;
            try (var store = new ChatStore(config, true)) { id = store.authenticate("root", "root").getUserId(); }
            try (var store = new ChatStore(config, false)) { assertNull(store.authenticate("root", "root")); }
            try (var store = new ChatStore(config, true)) { store.changePassword(id, "root", "personal-password"); }
            try (var store = new ChatStore(config, true)) {
                assertNull(store.authenticate("root", "root")); assertEquals(id, store.authenticate("root", "personal-password").getUserId());
            }
            try (var store = new ChatStore(config, false)) { assertEquals(id, store.authenticate("root", "personal-password").getUserId()); }
        });
    }
    @Test public void migratesV1AndV2WithoutClaimingIdentitiesOrLosingHistory() throws Exception {
        for (int oldVersion : new int[]{1, 2}) {
            final int version = oldVersion;
            withFile(config -> {
                try (var connection = DriverManager.getConnection(config.url()); var statement = connection.createStatement()) {
                    for (String migration : version == 1 ? List.of("V1__chat.sql") : List.of("V1__chat.sql", "V2__message_retry.sql")) {
                        String sql;
                        try (var resource = ChatStore.class.getResourceAsStream("/db/migration/" + migration)) {
                            sql = new String(Objects.requireNonNull(resource).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("__SEQUENCE__", "INTEGER");
                        }
                        for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
                    }
                    statement.execute("CREATE TABLE xim_schema_version(version INTEGER PRIMARY KEY)");
                    statement.execute("INSERT INTO xim_schema_version VALUES (" + version + ")");
                    statement.execute("INSERT INTO xim_users(user_id,username) VALUES ('stable-root','root'),('stable-aaa','aaa')");
                    statement.execute("INSERT INTO xim_groups VALUES ('old-group')");
                    statement.execute("INSERT INTO xim_members VALUES ('old-group','stable-root')");
                    statement.execute("INSERT INTO xim_messages(message_id,sender_id,recipient_id,body,sent_at) VALUES ('old-message','stable-root','stable-aaa','old history',123)");
                } catch (Exception failure) { throw new AssertionError(failure); }
                try (var store = new ChatStore(config, false)) {
                    assertNull(store.authenticate("root", "root")); assertEquals(0, store.credentialVersion("stable-root"));
                    assertThrows(IllegalArgumentException.class, () -> store.register("root", "attempted-claim"));
                    assertEquals("stable-root", store.provisionAccount("root", "operator-password").getUserId());
                    assertEquals("stable-root", store.authenticate("root", "operator-password").getUserId());
                    assertTrue(store.isMember("old-group", "stable-root"));
                    var message = store.history("stable-root", "user", "stable-aaa", null, 50).messages().getFirst();
                    assertEquals("old-message", message.getId()); assertEquals("old history", message.getMessage()); assertEquals(123, message.getTime());
                }
                try (var connection = DriverManager.getConnection(config.url()); var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT MAX(version) FROM xim_schema_version")) {
                    rs.next(); assertEquals(6, rs.getInt(1));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }
    private static void withFile(java.util.function.Consumer<DatabaseConfig> work) throws Exception {
        Path directory = Files.createTempDirectory("xim-accounts-");
        try { work.accept(DatabaseConfig.sqlite(directory.resolve("chat.db"))); }
        finally { try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
