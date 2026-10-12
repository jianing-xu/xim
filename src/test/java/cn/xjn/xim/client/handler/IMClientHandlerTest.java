package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class IMClientHandlerTest extends ChannelTestSupport {
    @Test
    public void sharedHandlerSupportsMultipleClientConnections() {
        EmbeddedChannel first = channel(IMClientHandler.INSTANCE);
        EmbeddedChannel second = channel(IMClientHandler.INSTANCE);
        assertTrue(first.isActive());
        assertTrue(second.isActive());
        assertFalse(first.writeInbound(new HeartbeatResponsePacket()));
        assertFalse(second.writeInbound(new HeartbeatResponsePacket()));
    }

    @Test
    public void dispatchesLoginAndLogoutAndClearsIdentity() {
        EmbeddedChannel channel = channel(IMClientHandler.INSTANCE);
        LoginResponsePacket login = new LoginResponsePacket();
        login.setSuccess(true);
        login.setUserId(java.util.UUID.randomUUID().toString());
        login.setUsername("Alice");
        assertFalse(channel.writeInbound(login));
        assertTrue(SessionManager.hasLogin(channel));
        LogoutResponsePacket logout = new LogoutResponsePacket();
        logout.setSuccess(false);
        channel.writeInbound(logout);
        assertTrue(SessionManager.hasLogin(channel));
        logout.setSuccess(true);
        channel.writeInbound(logout);
        assertFalse(SessionManager.hasLogin(channel));
        assertNull(SessionManager.getChannel(login.getUserId()));
    }

    @Test
    public void consumesAllResponseTypesIncludingFailureBranches() {
        EmbeddedChannel channel = channel(IMClientHandler.INSTANCE);
        CreateGroupResponsePacket create = new CreateGroupResponsePacket();
        create.setGroupId("group-1");
        create.setUsernameList(List.of("Alice", "Bob"));
        create.setReason("reason");
        JoinGroupResponsePacket join = new JoinGroupResponsePacket();
        join.setGroupId("group-1");
        join.setReason("reason");
        QuitGroupResponsePacket quit = new QuitGroupResponsePacket();
        quit.setGroupId("group-1");
        quit.setReason("reason");
        ListGroupMemberResponsePacket list = new ListGroupMemberResponsePacket();
        list.setGroupId("group-1");
        list.setSessions(List.of(new Session("user-1", "Alice")));
        GroupMessageResponsePacket group = new GroupMessageResponsePacket();
        group.setFromGroupId("group-1");
        group.setFromUser("Alice");
        group.setMessage("hello");
        for (boolean success : new boolean[]{false, true}) {
            create.setSuccess(success);
            join.setSuccess(success);
            quit.setSuccess(success);
            for (Packet packet : List.of(create, join, quit, list, group,
                    new MessageResponsePacket("user-1", "Alice", "hello"), new HeartbeatResponsePacket())) {
                assertFalse(channel.writeInbound(packet));
                assertNull(channel.readInbound());
                assertNull(channel.readOutbound());
            }
        }
    }

    @Test
    public void disconnectClearsClientSession() {
        EmbeddedChannel channel = channel(IMClientHandler.INSTANCE);
        Session session = login(channel, "Alice");
        channel.close();
        assertFalse(SessionManager.hasLogin(channel));
        assertNull(SessionManager.getChannel(session.getUserId()));
    }
}
