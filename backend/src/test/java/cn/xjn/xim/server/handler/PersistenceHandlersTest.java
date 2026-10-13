package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PersistenceHandlersTest extends ChannelTestSupport {
    @Test public void reloginRestoresMembershipAndStableIdentity() {
        var alice = channel(LoginRequestHandler.INSTANCE, IMServerHandler.INSTANCE);
        var bob = channel(); var a = login(alice,"root"); var b = login(bob,"aaa");
        String group = group(alice,bob);
        alice.writeInbound(new LogoutRequestPacket()); assertTrue(((LogoutResponsePacket)alice.readOutbound()).isSuccess());
        assertTrue(Storage.get().isMember(group,a.getUserId()));
        var request = new LoginRequestPacket(); request.setUsername("root"); request.setPassword("root");
        alice.writeInbound(request);
        assertEquals(a.getUserId(),((LoginResponsePacket)alice.readOutbound()).getUserId());
        assertTrue(SessionManager.getChannelGroup(group).contains(alice));
        assertTrue(Storage.get().isMember(group,b.getUserId()));
    }
    @Test public void replacementConnectionRevokesOldSessionAndKeepsNewMapping() {
        var old = channel(LoginRequestHandler.INSTANCE); var replacement = channel(LoginRequestHandler.INSTANCE);
        var request = new LoginRequestPacket(); request.setUsername("root"); request.setPassword("root");
        old.writeInbound(request); String id = ((LoginResponsePacket)old.readOutbound()).getUserId();
        request = new LoginRequestPacket(); request.setUsername("root"); request.setPassword("root");
        replacement.writeInbound(request);
        assertEquals(id,((LoginResponsePacket)replacement.readOutbound()).getUserId());
        assertFalse(old.isActive()); assertFalse(SessionManager.hasLogin(old));
        assertSame(replacement,SessionManager.getChannel(id));
        SessionManager.unbindSession(old); assertSame(replacement,SessionManager.getChannel(id));
    }
    @Test public void savesOfflinePrivateMessageAndOnlyAcknowledgesAcceptedWrites() {
        var sender = channel(IMServerHandler.INSTANCE); var recipient = channel();
        var alice = login(sender,"Alice"); var bob = login(recipient,"Bob");
        SessionManager.unbindSession(recipient);
        String clientId = java.util.UUID.randomUUID().toString();
        var request = new MessageRequestPacket(bob.getUserId(),"离线 🌍"); request.setRequestId(clientId);
        sender.writeInbound(request); var ack = (SentResponsePacket)sender.readOutbound();
        assertEquals(clientId,ack.getRequestId()); assertNotNull(ack.getId());
        var stored = Storage.get().history(bob.getUserId(),"user",alice.getUserId(),null,100).messages();
        assertEquals(ack.getId(),stored.getLast().getId());
        int count = stored.size();
        request = new MessageRequestPacket("missing","reject"); request.setRequestId("reject"); sender.writeInbound(request);
        assertEquals("reject",((ErrorResponsePacket)sender.readOutbound()).getRequestId()); assertNull(sender.readOutbound());
        assertEquals(count,Storage.get().history(bob.getUserId(),"user",alice.getUserId(),null,100).messages().size());
    }
    @Test public void enforcesTcpHistoryAndSendPermissionsAndRejectsOversizedMessages() {
        var member = channel(IMServerHandler.INSTANCE); var outsider = channel(IMServerHandler.INSTANCE);
        login(member,"Alice"); login(outsider,"Eve"); String id = group(member);
        var history = new HistoryRequestPacket(); history.setKind("group"); history.setId(id); history.setRequestId("history");
        outsider.writeInbound(history); assertEquals("history",((ErrorResponsePacket)outsider.readOutbound()).getRequestId());
        var message = new GroupMessageRequestPacket(id,"forbidden"); message.setRequestId("group");
        outsider.writeInbound(message); assertEquals("group",((ErrorResponsePacket)outsider.readOutbound()).getRequestId());
        message = new GroupMessageRequestPacket(id,"x".repeat(4001)); message.setRequestId("large");
        member.writeInbound(message); assertEquals("large",((ErrorResponsePacket)member.readOutbound()).getRequestId());
        assertNull(member.readOutbound());
        member.writeInbound(history); assertTrue(((HistoryResponsePacket)member.readOutbound()).getMessages().isEmpty());
    }
}
