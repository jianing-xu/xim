package cn.xjn.xim.storage;

import org.junit.Test;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.Assert.*;

public class ContactStoreTest {
    public static void directoryContract(DatabaseConfig config) {
        String prefix = "dir_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String self, peer;
        try (var store = new ChatStore(config, false)) {
            var me = store.register(prefix + "_me", "private-password"); self = me.getUserId();
            peer = store.register(prefix + "_peer", "private-password", prefix + " 小林_100%").getUserId();
            store.register(prefix + "_other", "private-password", prefix + " OTHER");
            store.register(prefix + "_last", "private-password");
            store.user(prefix + "_unprovisioned");
            var first = store.contacts(self, prefix.toUpperCase(Locale.ROOT), null, 1);
            assertTrue(first.hasMore()); assertEquals(1, first.users().size());
            Set<String> ids = new HashSet<>(); String after = null; int pages = 0;
            do {
                var page = store.contacts(self, prefix, after, 1);
                assertEquals(1, page.users().size());
                assertTrue(ids.add(page.users().getFirst().getUserId()));
                after = page.nextAfter(); pages++;
            } while (after != null);
            assertEquals(3, pages); assertFalse(ids.contains(self)); assertTrue(ids.contains(peer));
            assertEquals(peer, store.contacts(self, "小林_100%", null, 20).users().getFirst().getUserId());
            assertEquals(1, store.contacts(self, "_100%", null, 20).users().size());
            assertTrue(store.contacts(self, "' OR 1=1 --", null, 20).users().isEmpty());
            assertTrue(store.contacts(self, prefix + "_missing", null, 20).users().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.contacts("missing", "", null, 20));
            assertThrows(IllegalArgumentException.class, () -> store.contacts(self, "", null, 0));
            assertThrows(IllegalArgumentException.class, () -> store.contacts(self, "", null, 51));
            assertThrows(IllegalArgumentException.class, () -> store.contacts(self, "x".repeat(65), null, 20));
            assertThrows(IllegalArgumentException.class, () -> store.contacts(self, "", "", 20));
            assertThrows(IllegalArgumentException.class, () -> store.register(prefix + "_bad", "private-password", "bad\nname"));
            assertThrows(IllegalArgumentException.class, () -> store.register(prefix + "_bad", "private-password", " "));
            assertThrows(IllegalArgumentException.class, () -> store.register(prefix + "_bad", "private-password", "x".repeat(65)));
            store.save(me, peer, null, "first offline greeting");
            assertEquals(prefix + " 小林_100%", store.conversations(self).getFirst().getName());
        }
        try (var store = new ChatStore(config, false)) {
            assertEquals(peer, store.contacts(self, "小林_100%", null, 20).users().getFirst().getUserId());
            assertEquals(1, store.conversations(peer).getFirst().getUnread());
        }
    }
    @Test public void directorySearchPagesFiltersAndNamesSurviveRestart() throws Exception {
        withFile(ContactStoreTest::directoryContract);
    }
    @Test public void v5UpgradePreservesIdentityCredentialsMessagesMembershipReadsAndSession() throws Exception {
        withFile(config -> {
            String a, b, message, cookie;
            try (var store = new ChatStore(config, false)) {
                var alice = store.register("old_alice", "private-password"); a = alice.getUserId();
                b = store.register("old_bob", "private-password").getUserId();
                store.createGroup("old-group", List.of(a, b));
                var saved = store.save(alice, b, null, "old data"); message = saved.getId();
                store.markRead(b, "user", a, String.valueOf(saved.getSequence()));
                cookie = store.redeemTicket(store.issueSession(a).ticket()).token();
            }
            try (var conn = DriverManager.getConnection(config.url()); var sql = conn.createStatement()) {
                sql.execute("ALTER TABLE xim_users DROP COLUMN display_name");
                sql.execute("DELETE FROM xim_schema_version WHERE version>=6");
            } catch (Exception failure) { throw new AssertionError(failure); }
            try (var store = new ChatStore(config, false)) {
                assertEquals(a, store.authenticate("old_alice", "private-password").getUserId());
                assertEquals(a, store.resumeSession(cookie).user().getUserId());
                assertTrue(store.isMember("old-group", b));
                assertEquals(message, store.history(b, "user", a, null, 20).messages().getFirst().getId());
                assertEquals(0, store.conversations(b).stream().filter(c -> c.getKind().equals("user")).findFirst().orElseThrow().getUnread());
                assertEquals("old_bob", store.contacts(a, "old_bob", null, 20).users().getFirst().getDisplayName());
            }
        });
    }
    @Test public void disabledDemoAndUnprovisionedAccountsAreNotDiscoverable() throws Exception {
        withFile(config -> {
            try (var store = new ChatStore(config, true)) { store.user("legacy_user"); }
            try (var store = new ChatStore(config, false)) {
                var id = store.register("real_user", "private-password").getUserId();
                assertTrue(store.contacts(id, "", null, 50).users().isEmpty());
            }
        });
    }
    private static void withFile(java.util.function.Consumer<DatabaseConfig> work) throws Exception {
        var dir = Files.createTempDirectory("xim-contacts-");
        try { work.accept(DatabaseConfig.sqlite(dir.resolve("chat.db"))); }
        finally { try (var paths = Files.walk(dir)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
}
