package cn.xjn.xim.storage;

import org.junit.Test;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.Assert.*;

public class ConversationReadStoreTest {
    private static ChatStore.Conversation chat(ChatStore store, String user, String kind, String id) {
        return store.conversations(user).stream().filter(c -> c.getKind().equals(kind) && c.getId().equals(id)).findFirst().orElseThrow();
    }
    /** SQL contract reused by the opt-in PostgreSQL integration test. */
    public static void readContract(DatabaseConfig config) {
        String suffix = UUID.randomUUID().toString();
        String group = UUID.randomUUID().toString();
        String a, b, c; long readPrivate, readGroup; String newest;
        try (var store = new ChatStore(config, false)) {
            var alice = store.user("read-a-" + suffix); var bob = store.user("read-b-" + suffix); var outsider = store.user("read-c-" + suffix);
            a = alice.getUserId(); b = bob.getUserId(); c = outsider.getUserId();
            store.createGroup(group,List.of(a,b));
            ChatStore.StoredMessage firstGroup = null, lastPrivate = null;
            for (int i=0;i<65;i++) {
                lastPrivate = store.save(alice,b,null,"私聊 " + i,"p-" + i).message();
                var saved = store.save(alice,null,group,"群聊 " + i,"g-" + i).message();
                if (i==0) firstGroup = saved;
            }
            // Idempotent retries neither count twice nor advance the activity order.
            assertFalse(store.save(alice,b,null,"私聊 64","p-64").inserted());
            assertEquals(65,chat(store,b,"user",a).getUnread());
            assertEquals(65,chat(store,b,"group",group).getUnread());
            assertEquals(0,chat(store,a,"user",b).getUnread());
            var own = store.save(bob,a,null,"自己的回复");
            var ownGroup = store.save(bob,null,group,"自己的群回复");
            assertEquals(65,chat(store,b,"user",a).getUnread());
            assertEquals(65,chat(store,b,"group",group).getUnread());
            assertEquals("自己的回复",chat(store,b,"user",a).getLastMessage().getMessage());
            assertEquals(own.getSequence(),chat(store,b,"user",a).getLastSequence());
            assertEquals(own.getTime(),chat(store,b,"user",a).getLastTime());
            assertEquals(group,store.conversations(b).getFirst().getId());
            var recent = store.history(b,"group",group,null,50);
            assertEquals(50,recent.messages().size()); assertTrue(recent.hasMore());
            assertEquals(65,chat(store,b,"group",group).getUnread()); // Queries never mark read.
            readGroup = recent.messages().getLast().getSequence();
            assertEquals(ownGroup.getSequence(),readGroup);
            var read = store.markRead(b,"group",group,Long.toString(readGroup));
            assertEquals(0,read.getUnread()); assertEquals(readGroup,read.getReadSequence());
            assertEquals(readGroup,store.markRead(b,"group",group,Long.toString(firstGroup.getSequence())).getReadSequence());
            assertEquals(readGroup,store.markRead(b,"group",group,Long.toString(readGroup)).getReadSequence());
            var older = store.history(b,"group",group,recent.nextBefore(),50);
            assertEquals(16,older.messages().size()); assertFalse(older.hasMore());
            assertEquals(0,chat(store,b,"group",group).getUnread());
            var foreign = store.save(outsider,a,null,"unrelated");
            assertThrows(IllegalArgumentException.class,() -> store.markRead(b,"group",group,Long.toString(foreign.getSequence())));
            assertThrows(IllegalArgumentException.class,() -> store.markRead(b,"user",a,Long.toString(Long.MAX_VALUE)));
            for (String invalid : Arrays.asList(null,"bad","0","-1"))
                assertThrows(IllegalArgumentException.class,() -> store.markRead(b,"user",a,invalid));
            assertThrows(IllegalArgumentException.class,() -> store.markRead(c,"group",group,Long.toString(readGroup)));
            assertThrows(IllegalArgumentException.class,() -> store.markRead(b,"user",b,"1"));
            assertThrows(IllegalArgumentException.class,() -> store.markRead(b,"invalid",a,"1"));
            assertThrows(IllegalArgumentException.class,() -> store.history(b,"user",a,"1","0",50));
            assertThrows(IllegalArgumentException.class,() -> store.history(b,"user",a,null,"-1",50));
            readPrivate = lastPrivate.getSequence();
            assertEquals(0,store.markRead(b,"user",a,Long.toString(readPrivate)).getUnread());
            newest = store.save(alice,b,null,"离线后的新私聊","after-read").message().getId();
            assertEquals(1,chat(store,b,"user",a).getUnread());
            var forward = store.history(b,"user",a,null,Long.toString(readPrivate),1);
            assertTrue(forward.hasMore()); assertEquals(own.getId(),forward.messages().getFirst().getId());
            var tail = store.history(b,"user",a,null,forward.nextAfter(),1);
            assertFalse(tail.hasMore()); assertEquals(newest,tail.messages().getFirst().getId());
            assertEquals(store.syncCursor(),tail.syncCursor());
            assertTrue(store.history(b,"user",a,null,tail.nextAfter(),50).messages().isEmpty());
        }
        try (var store = new ChatStore(config, false)) {
            assertEquals(readPrivate,chat(store,b,"user",a).getReadSequence());
            assertEquals(1,chat(store,b,"user",a).getUnread());
            assertEquals(newest,chat(store,b,"user",a).getLastMessage().getId());
            assertEquals(0,chat(store,b,"group",group).getUnread());
            assertEquals(readGroup,chat(store,b,"group",group).getReadSequence());
            assertEquals(a,store.conversations(b).getFirst().getId());
            store.join(group,b); assertEquals(readGroup,chat(store,b,"group",group).getReadSequence());
            store.quit(group,b);
            assertFalse(store.conversations(b).stream().anyMatch(chat -> chat.getId().equals(group)));
            assertThrows(IllegalArgumentException.class,() -> store.markRead(b,"group",group,Long.toString(readGroup)));
            assertThrows(IllegalArgumentException.class,() -> store.history(b,"group",group,null,50));
            var alice = store.user("read-a-" + suffix);
            var away = store.save(alice,null,group,"退出期间");
            store.join(group,b); store.join(group,b);
            assertEquals(0,chat(store,b,"group",group).getUnread());
            assertEquals(away.getSequence(),chat(store,b,"group",group).getReadSequence());
            assertEquals(67,store.history(b,"group",group,null,100).messages().size());
            store.join(group,c); assertEquals(0,chat(store,c,"group",group).getUnread());
            store.save(alice,null,group,"重新加入后的新消息");
            assertEquals(1,chat(store,b,"group",group).getUnread());
            assertEquals(1,chat(store,c,"group",group).getUnread());
            assertEquals(group,store.conversations(b).getFirst().getId());
        }
    }
    @Test public void unreadAndReadingSurviveReopenWithPaginationPermissionsAndMembershipLifecycle() throws Exception {
        withFile(ConversationReadStoreTest::readContract);
    }
    @Test public void v3UpgradeKeepsExistingIdentityHistoryAndInitialUnread() throws Exception {
        withFile(config -> {
            String a,b,message;
            try (var store = new ChatStore(config,false)) {
                var alice = store.user("alice"); var bob = store.user("bob"); a=alice.getUserId(); b=bob.getUserId();
                message=store.save(alice,b,null,"V3 历史").getId();
            }
            try (var connection = DriverManager.getConnection(config.url()); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE xim_users DROP COLUMN display_name");statement.execute("DROP TABLE xim_sessions"); statement.execute("DROP TABLE xim_conversation_reads"); statement.execute("DELETE FROM xim_schema_version WHERE version>=4");
            } catch (Exception failure) { throw new AssertionError(failure); }
            try (var store = new ChatStore(config,false)) {
                assertEquals(a,store.user("alice").getUserId()); assertEquals(b,store.user("bob").getUserId());
                var recovered=chat(store,b,"user",a); assertEquals(1,recovered.getUnread()); assertEquals(0,recovered.getReadSequence());
                assertEquals(message,recovered.getLastMessage().getId());
            }
        });
    }
    private static void withFile(java.util.function.Consumer<DatabaseConfig> work) throws Exception {
        Path directory=Files.createTempDirectory("xim-reads-");
        try { work.accept(DatabaseConfig.sqlite(directory.resolve("chat.db"))); }
        finally { try (var paths=Files.walk(directory)) { for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
