package cn.xjn.xim.storage;

import org.junit.Test;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class ChatStoreTest {
    /** Runs the same real SQL contract for SQLite and opt-in PostgreSQL. */
    public static void recoveryContract(DatabaseConfig config) {
        String aliceId, bobId, messageId;
        String group = java.util.UUID.randomUUID().toString();
        try (var store = new ChatStore(config)) {
            var alice = store.user("root"); var bob = store.user("aaa");
            aliceId = alice.getUserId(); bobId = bob.getUserId();
            store.createGroup(group, List.of(aliceId,bobId));
            messageId = store.save(alice,bobId,null,"你好 🌍\n'sql' <script>").getId();
            for (int i = 0; i < 5; i++) store.save(alice,null,group,"群聊 " + i);
            // Atomic creation: a foreign-key failure cannot leave an orphan group.
            String bad = java.util.UUID.randomUUID().toString();
            assertThrows(IllegalStateException.class, () -> store.createGroup(bad,List.of(aliceId,"missing")));
            assertFalse(store.hasGroup(bad));
        }
        try (var store = new ChatStore(config)) {
            assertEquals(aliceId,store.user("root").getUserId());
            assertEquals(bobId,store.user("aaa").getUserId());
            assertTrue(store.isMember(group,bobId));
            assertEquals(2,store.members(group).size());
            assertTrue(store.conversations(bobId).stream().anyMatch(chat -> chat.getKind().equals("group") && chat.getId().equals(group)));
            var privateHistory = store.history(bobId,"user",aliceId,null,50);
            assertEquals(messageId,privateHistory.messages().getLast().getId());
            assertEquals("你好 🌍\n'sql' <script>",privateHistory.messages().getLast().getMessage());
            var recent = store.history(bobId,"group",group,null,2);
            assertTrue(recent.hasMore()); assertEquals("群聊 3",recent.messages().getFirst().getMessage());
            var older = store.history(bobId,"group",group,recent.nextBefore(),2);
            assertTrue(older.hasMore()); assertEquals("群聊 1",older.messages().getFirst().getMessage());
            var oldest = store.history(bobId,"group",group,older.nextBefore(),2);
            assertFalse(oldest.hasMore()); assertEquals("群聊 0",oldest.messages().getFirst().getMessage());
            String outsider = store.user("bbb").getUserId();
            assertThrows(IllegalArgumentException.class, () -> store.history(outsider,"group",group,null,50));
            assertThrows(IllegalArgumentException.class, () -> store.save(store.user("bbb"),null,group,"forbidden"));
            assertTrue(store.history(outsider,"user",aliceId,null,50).messages().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.history(bobId,"group",group,null,101));
            assertThrows(IllegalArgumentException.class, () -> store.history(bobId,"group",group,"bad",50));
            store.quit(group,bobId);
        }
        try (var store = new ChatStore(config)) {
            assertFalse(store.isMember(group,bobId));
            assertThrows(IllegalArgumentException.class, () -> store.history(bobId,"group",group,null,50));
            store.join(group,bobId); store.join(group,bobId);
            assertEquals(2,store.members(group).size());
            assertEquals(5,store.history(bobId,"group",group,null,50).messages().size());
        }
    }
    @Test public void sqliteWritesAndRecoversRealFileWithAtomicityAndPagination() throws Exception {
        var directory = Files.createTempDirectory("xim-store-");
        var file = directory.resolve("nested/chat.db");
        try { recoveryContract(DatabaseConfig.sqlite(file)); assertTrue(Files.size(file) > 0); }
        finally {
            try (var files = Files.walk(directory)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    @Test public void rejectsNewerSchemaWithoutChangingIt() throws Exception {
        var file = Files.createTempFile("xim-newer-", ".db");
        try {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE xim_schema_version(version INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO xim_schema_version VALUES (99)");
            }
            assertThrows(IllegalStateException.class, () -> new ChatStore(DatabaseConfig.sqlite(file)));
        } finally { Files.deleteIfExists(file); }
    }
}
