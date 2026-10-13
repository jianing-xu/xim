package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class GroupRequestHandlersTest extends ChannelTestSupport {
    @Test
    public void createsGroupForOnlineUsersAndBroadcastsResult() {
        EmbeddedChannel alice = channel(IMServerHandler.INSTANCE);
        EmbeddedChannel bob = channel();
        EmbeddedChannel outsider = channel();
        Session first = login(alice, "Alice");
        Session second = login(bob, "Bob");
        login(outsider, "Eve");
        CreateGroupRequestPacket request = new CreateGroupRequestPacket();
        request.setUserIds(List.of(first.getUserId(), second.getUserId()));
        alice.writeInbound(request);
        CreateGroupResponsePacket response = alice.readOutbound();
        trackGroup(response.getGroupId());
        assertTrue(response.isSuccess());
        assertEquals(List.of("Alice", "Bob"), response.getUsernameList());
        assertSame(response, bob.readOutbound());
        assertEquals(2, SessionManager.getChannelGroup(response.getGroupId()).size());
        assertNull(outsider.readOutbound());
    }

    @Test
    public void joinsExistingGroupIdempotently() {
        EmbeddedChannel member = channel();
        EmbeddedChannel joining = channel(IMServerHandler.INSTANCE);
        login(member, "Alice");
        login(joining, "Bob");
        String id = group(member);
        JoinGroupRequestPacket request = new JoinGroupRequestPacket();
        request.setGroupId(id);
        for (int i = 0; i < 2; i++) {
            joining.writeInbound(request);
            JoinGroupResponsePacket response = joining.readOutbound();
            assertTrue(response.isSuccess());
            assertEquals(id, response.getGroupId());
        }
        assertTrue(SessionManager.getChannelGroup(id).contains(joining));
        assertEquals(2, SessionManager.getChannelGroup(id).size());
    }

    @Test
    public void rejectsJoiningMissingGroup() {
        EmbeddedChannel joining = channel(JoinGroupRequestHandler.INSTANCE);
        login(joining, "Bob");
        JoinGroupRequestPacket request = new JoinGroupRequestPacket();
        request.setGroupId("missing");
        joining.writeInbound(request);
        JoinGroupResponsePacket response = joining.readOutbound();
        assertFalse(response.isSuccess());
        assertEquals("missing", response.getGroupId());
        assertEquals("Group does not exist", response.getReason());
        assertTrue(joining.isActive());
    }

    @Test
    public void listsMemberSessions() {
        EmbeddedChannel alice = channel(IMServerHandler.INSTANCE);
        EmbeddedChannel bob = channel();
        Session first = login(alice, "Alice");
        Session second = login(bob, "Bob");
        String id = group(alice, bob);
        ListGroupMemberRequestPacket request = new ListGroupMemberRequestPacket();
        request.setGroupId(id);
        alice.writeInbound(request);
        ListGroupMemberResponsePacket response = alice.readOutbound();
        assertEquals(id, response.getGroupId());
        assertEquals(Set.of(first, second), Set.copyOf(response.getSessions()));
        assertNull(bob.readOutbound());
    }

    @Test
    public void broadcastsGroupMessageOnlyToMembers() {
        EmbeddedChannel alice = channel(IMServerHandler.INSTANCE);
        EmbeddedChannel bob = channel();
        EmbeddedChannel outsider = channel();
        login(alice, "Alice");
        login(bob, "Bob");
        login(outsider, "Eve");
        String id = group(alice, bob);
        alice.writeInbound(new GroupMessageRequestPacket(id, "你好 🌍"));
        GroupMessageResponsePacket response = bob.readOutbound();
        assertEquals(id, response.getFromGroupId());
        assertEquals("Alice", response.getFromUser());
        assertEquals("你好 🌍", response.getMessage());
        assertSame(response, alice.readOutbound());
        assertNull(outsider.readOutbound());
    }

    @Test
    public void quitRemovesOnlyRequestingMember() {
        EmbeddedChannel alice = channel(IMServerHandler.INSTANCE);
        EmbeddedChannel bob = channel();
        login(alice, "Alice");
        login(bob, "Bob");
        String id = group(alice, bob);
        QuitGroupRequestPacket request = new QuitGroupRequestPacket();
        request.setGroupId(id);
        alice.writeInbound(request);
        QuitGroupResponsePacket response = alice.readOutbound();
        assertTrue(response.isSuccess());
        assertEquals(id, response.getGroupId());
        assertFalse(SessionManager.getChannelGroup(id).contains(alice));
        assertTrue(SessionManager.getChannelGroup(id).contains(bob));
        assertNull(bob.readOutbound());
    }

    @Test
    public void quitMissingGroupDoesNotCloseConnection() {
        EmbeddedChannel alice = channel(IMServerHandler.INSTANCE);
        login(alice, "Alice");
        QuitGroupRequestPacket request = new QuitGroupRequestPacket();
        request.setGroupId("missing");
        assertFalse(alice.writeInbound(request));
        assertTrue(alice.isActive());
        assertEquals("群组不存在", ((ErrorResponsePacket) alice.readOutbound()).getReason());
    }
}
