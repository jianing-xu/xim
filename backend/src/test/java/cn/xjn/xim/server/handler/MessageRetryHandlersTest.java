package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.support.ChannelTestSupport;
import org.junit.*;
import java.nio.file.*;
import java.sql.DriverManager;
import static org.junit.Assert.*;

public class MessageRetryHandlersTest extends ChannelTestSupport {
    private Path database;
    private String previous;
    @Before public void isolatedDatabase() throws Exception {
        Storage.close(); previous = System.getProperty("xim.db.sqlite.path");
        database = Files.createTempFile("xim-handler-retry-", ".db");
        System.setProperty("xim.db.sqlite.path", database.toString());
    }
    @After public void restoreDatabase() throws Exception {
        Storage.close();
        if (previous == null) System.clearProperty("xim.db.sqlite.path"); else System.setProperty("xim.db.sqlite.path", previous);
        Files.deleteIfExists(database);
        Files.deleteIfExists(Path.of(database + "-wal")); Files.deleteIfExists(Path.of(database + "-shm"));
    }
    @Test public void privateRetryReturnsOriginalReceiptWithoutRebroadcast() {
        var sender = channel(IMServerHandler.INSTANCE); var recipient = channel();
        var alice = login(sender, "root"); var bob = login(recipient, "aaa");
        var request = new MessageRequestPacket(bob.getUserId(), "body"); request.setRequestId("private");
        sender.writeInbound(request); var first = (SentResponsePacket)sender.readOutbound();
        assertEquals(first.getId(), ((MessageResponsePacket)recipient.readOutbound()).getId());
        sender.writeInbound(request); var repeated = (SentResponsePacket)sender.readOutbound();
        assertEquals(first.getId(), repeated.getId()); assertEquals(first.getTime(), repeated.getTime());
        assertNull(recipient.readOutbound()); assertNull(sender.readOutbound());
        assertEquals(1, Storage.get().history(alice.getUserId(), "user", bob.getUserId(), null, 50).messages().size());
        request.setMessage("different"); sender.writeInbound(request);
        assertEquals("private", ((ErrorResponsePacket)sender.readOutbound()).getRequestId());
        assertNull(recipient.readOutbound()); assertNull(sender.readOutbound());
    }
    @Test public void groupRetryReturnsOriginalReceiptWithoutRebroadcastToSenderOrMembers() {
        var sender = channel(IMServerHandler.INSTANCE); var recipient = channel();
        var alice = login(sender, "root"); login(recipient, "aaa"); String groupId = group(sender, recipient);
        var request = new GroupMessageRequestPacket(groupId, "body"); request.setRequestId("group");
        sender.writeInbound(request);
        var echo = (GroupMessageResponsePacket)sender.readOutbound(); var first = (SentResponsePacket)sender.readOutbound();
        assertEquals("group", echo.getRequestId()); assertEquals(first.getId(), echo.getId());
        assertEquals(first.getId(), ((GroupMessageResponsePacket)recipient.readOutbound()).getId());
        sender.writeInbound(request); var repeated = (SentResponsePacket)sender.readOutbound();
        assertEquals(first.getId(), repeated.getId()); assertEquals(first.getTime(), repeated.getTime());
        assertNull(sender.readOutbound()); assertNull(recipient.readOutbound());
        assertEquals(1, Storage.get().history(alice.getUserId(), "group", groupId, null, 50).messages().size());
    }
    @Test public void databaseFailureReturnsNoSuccessAndAllowsSameIdRetryAfterRecovery() throws Exception {
        var sender = channel(IMServerHandler.INSTANCE); var recipient = channel();
        var alice = login(sender, "root"); var bob = login(recipient, "aaa"); String groupId = group(sender, recipient);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            // Real SQL write failure, after validation, for both send handlers.
            statement.execute("CREATE TRIGGER reject_messages BEFORE INSERT ON xim_messages BEGIN SELECT RAISE(ABORT, 'test write failure'); END");
            var privateRequest = new MessageRequestPacket(bob.getUserId(), "private"); privateRequest.setRequestId("failed-private");
            var groupRequest = new GroupMessageRequestPacket(groupId, "group"); groupRequest.setRequestId("failed-group");
            sender.writeInbound(privateRequest); assertEquals("failed-private", ((ErrorResponsePacket)sender.readOutbound()).getRequestId());
            sender.writeInbound(groupRequest); assertEquals("failed-group", ((ErrorResponsePacket)sender.readOutbound()).getRequestId());
            assertNull(sender.readOutbound()); assertNull(recipient.readOutbound());
            assertTrue(Storage.get().history(alice.getUserId(), "user", bob.getUserId(), null, 50).messages().isEmpty());
            assertTrue(Storage.get().history(alice.getUserId(), "group", groupId, null, 50).messages().isEmpty());
            statement.execute("DROP TRIGGER reject_messages");
            sender.writeInbound(privateRequest); assertNotNull(((SentResponsePacket)sender.readOutbound()).getId()); recipient.readOutbound();
            sender.writeInbound(groupRequest); assertNotNull(((GroupMessageResponsePacket)sender.readOutbound()).getId());
            assertNotNull(((SentResponsePacket)sender.readOutbound()).getId());
        }
    }
}
