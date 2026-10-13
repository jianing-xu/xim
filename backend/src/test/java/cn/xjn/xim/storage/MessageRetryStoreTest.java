package cn.xjn.xim.storage;

import org.junit.Test;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class MessageRetryStoreTest {
    /** Also run by the opt-in PostgreSQL test, using a fresh schema. */
    public static void retryContract(DatabaseConfig config) throws Exception {
        String senderId, recipientId, privateId, groupId = UUID.randomUUID().toString(), savedGroupId;
        long privateTime, groupTime;
        try (var store = new ChatStore(config)) {
            var sender = store.user("root"); var recipient = store.user("aaa");
            senderId = sender.getUserId(); recipientId = recipient.getUserId();
            store.createGroup(groupId, List.of(senderId, recipientId));
            var first = store.save(sender, recipientId, null, "可靠 🌍", "private-retry");
            privateId = first.message().getId(); privateTime = first.message().getTime();
            assertTrue(first.inserted());
            var duplicate = store.save(sender, recipientId, null, "可靠 🌍", "private-retry");
            assertFalse(duplicate.inserted()); assertEquals(first.message(), duplicate.message());
            var group = store.save(sender, null, groupId, "群聊", "group-retry");
            savedGroupId = group.message().getId(); groupTime = group.message().getTime();
            assertFalse(store.save(sender, null, groupId, "群聊", "group-retry").inserted());
            assertThrows(IllegalArgumentException.class, () -> store.save(sender, recipientId, null, "changed", "private-retry"));
            assertThrows(IllegalArgumentException.class, () -> store.save(sender, store.user("bbb").getUserId(), null, "可靠 🌍", "private-retry"));
            assertThrows(IllegalArgumentException.class, () -> store.save(sender, null, groupId, "可靠 🌍", "private-retry"));
            String otherGroup = UUID.randomUUID().toString(); store.createGroup(otherGroup, List.of(senderId));
            assertThrows(IllegalArgumentException.class, () -> store.save(sender, null, otherGroup, "群聊", "group-retry"));
            assertThrows(IllegalArgumentException.class, () -> store.save(sender, recipientId, null, "body", " "));
            assertThrows(IllegalArgumentException.class, () -> store.save(sender, recipientId, null, "body", "x".repeat(81)));
            // Client IDs are scoped to the sender; two users may choose the same ID.
            assertTrue(store.save(recipient, senderId, null, "reply", "private-retry").inserted());
            // Legacy TCP callers remain compatible, with independent unnumbered messages.
            assertNotEquals(store.save(sender, recipientId, null, "legacy").getId(), store.save(sender, recipientId, null, "legacy").getId());
        }
        try (var store = new ChatStore(config); var other = new ChatStore(config); var executor = Executors.newFixedThreadPool(8)) {
            var sender = store.user("root");
            assertEquals(senderId, sender.getUserId());
            var replay = store.save(sender, recipientId, null, "可靠 🌍", "private-retry");
            assertFalse(replay.inserted()); assertEquals(privateId, replay.message().getId()); assertEquals(privateTime, replay.message().getTime());
            replay = store.save(sender, null, groupId, "群聊", "group-retry");
            assertFalse(replay.inserted()); assertEquals(savedGroupId, replay.message().getId()); assertEquals(groupTime, replay.message().getTime());
            assertTrue(store.history(senderId, "user", recipientId, null, 100).messages().stream().anyMatch(message -> message.getId().equals(privateId) && "private-retry".equals(message.getClientMessageId())));
            for (boolean group : List.of(false, true)) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<ChatStore.SaveResult>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    ChatStore target = i % 2 == 0 ? store : other;
                    futures.add(executor.submit(() -> {
                        start.await();
                        return target.save(sender, group ? null : recipientId, group ? groupId : null, "concurrent", "concurrent-" + group);
                    }));
                }
                start.countDown(); int inserts = 0; Set<String> ids = new HashSet<>(); Set<Long> times = new HashSet<>();
                for (var future : futures) {
                    var result = future.get(10, TimeUnit.SECONDS);
                    if (result.inserted()) inserts++;
                    ids.add(result.message().getId()); times.add(result.message().getTime());
                }
                assertEquals(1, inserts); assertEquals(1, ids.size()); assertEquals(1, times.size());
                var history = store.history(senderId, group ? "group" : "user", group ? groupId : recipientId, null, 100);
                assertEquals(1, history.messages().stream().filter(message -> message.getMessage().equals("concurrent")).count());
            }
        }
    }
    @Test public void sqliteConcurrentRetriesSurviveReopening() throws Exception {
        Path directory = Files.createTempDirectory("xim-retry-");
        try { retryContract(DatabaseConfig.sqlite(directory.resolve("chat.db"))); }
        finally { deleteDirectory(directory); }
    }
    @Test public void migratesV1WithoutChangingIdentityOrHistory() throws Exception {
        Path directory = Files.createTempDirectory("xim-v1-"); Path file = directory.resolve("chat.db");
        try {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
                String sql;
                try (var resource = ChatStore.class.getResourceAsStream("/db/migration/V1__chat.sql")) {
                    sql = new String(Objects.requireNonNull(resource).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("__SEQUENCE__", "INTEGER");
                }
                for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
                statement.execute("CREATE TABLE xim_schema_version(version INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO xim_schema_version VALUES (1)");
                statement.execute("INSERT INTO xim_users VALUES ('stable-root', 'root'), ('stable-aaa', 'aaa')");
                statement.execute("INSERT INTO xim_messages(message_id,sender_id,recipient_id,body,sent_at) VALUES ('old','stable-root','stable-aaa','old body',123)");
            }
            try (var store = new ChatStore(DatabaseConfig.sqlite(file))) {
                var root = store.user("root"); assertEquals("stable-root", root.getUserId());
                var old = store.history(root.getUserId(), "user", "stable-aaa", null, 50).messages().getFirst();
                assertEquals("old", old.getId()); assertEquals(123, old.getTime()); assertNull(old.getClientMessageId());
                assertTrue(store.save(root, "stable-aaa", null, "new", "new-id").inserted());
            }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT MAX(version) FROM xim_schema_version")) {
                result.next(); assertEquals(6, result.getInt(1));
            }
        } finally { deleteDirectory(directory); }
    }
    private static void deleteDirectory(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
