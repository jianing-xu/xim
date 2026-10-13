package cn.xjn.xim.integration;

import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class MessagingIT {
    private TcpTestServer server;
    private final List<TcpPeer> peers = new ArrayList<>();
    private final List<String> groupIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    @Before
    public void startServerOnEphemeralLoopbackPort() throws Exception {
        server = new TcpTestServer();
    }

    @After
    public void stopServerAndCleanRegistries() throws Exception {
        try {
            for (TcpPeer peer : peers) {
                peer.close();
            }
        } finally {
            try {
                if (server != null) {
                    server.close();
                }
            } finally {
                groupIds.forEach(SessionManager::unbindChannelGroup);
            }
        }
        for (String id : userIds) {
            assertNull("User mapping leaked after server shutdown", SessionManager.getChannel(id));
        }
    }

    @Test(timeout = 20000)
    public void failedLoginCanBeRetriedOnSameConnection() throws Exception {
        TcpPeer peer = peer();
        LoginRequestPacket invalid = new LoginRequestPacket();
        invalid.setUsername("root");
        invalid.setPassword("wrong");
        peer.send(invalid);
        LoginResponsePacket rejected = peer.receive(LoginResponsePacket.class);
        assertFalse(rejected.isSuccess());
        assertEquals(cn.xjn.xim.auth.AccountPolicy.INVALID_CREDENTIALS, rejected.getReason());
        assertNull(rejected.getUserId());
        LoginResponsePacket accepted = login(peer, "root");
        assertEquals("root", SessionManager.getSession(SessionManager.getChannel(accepted.getUserId())).getUsername());
        peer.heartbeat();
    }

    @Test(timeout = 20000)
    public void routesUnicodePrivateMessagesBetweenRealConnections() throws Exception {
        TcpPeer alice = peer();
        TcpPeer bob = peer();
        TcpPeer outsider = peer();
        LoginResponsePacket from = login(alice, "root");
        LoginResponsePacket to = login(bob, "aaa");
        login(outsider, "bbb");
        alice.send(new MessageRequestPacket(to.getUserId(), "你好 🌍"));
        MessageResponsePacket response = bob.receive(MessageResponsePacket.class);
        assertEquals(from.getUserId(), response.getFromUserId());
        assertEquals("root", response.getFromUsername());
        assertEquals("你好 🌍", response.getMessage());
        // Heartbeat replies also establish that no private message was queued to other clients.
        alice.heartbeat();
        outsider.heartbeat();
        bob.send(new MessageRequestPacket(from.getUserId(), "reply"));
        assertEquals("reply", alice.receive(MessageResponsePacket.class).getMessage());
    }

    @Test(timeout = 20000)
    public void createsJoinsListsBroadcastsAndQuitsGroupOverTcp() throws Exception {
        TcpPeer alice = peer();
        TcpPeer bob = peer();
        TcpPeer carol = peer();
        LoginResponsePacket a = login(alice, "root");
        LoginResponsePacket b = login(bob, "aaa");
        LoginResponsePacket c = login(carol, "bbb");
        String id = createGroup(alice, bob, a.getUserId(), b.getUserId());
        JoinGroupRequestPacket join = new JoinGroupRequestPacket();
        join.setGroupId(id);
        carol.send(join);
        JoinGroupResponsePacket joined = carol.receive(JoinGroupResponsePacket.class);
        assertTrue(joined.isSuccess());
        assertEquals(id, joined.getGroupId());
        assertEquals(Set.of(a.getUserId(), b.getUserId(), c.getUserId()), members(alice, id));
        alice.send(new GroupMessageRequestPacket(id, "群聊 🌍"));
        for (TcpPeer member : List.of(alice, bob, carol)) {
            GroupMessageResponsePacket message = member.receive(GroupMessageResponsePacket.class);
            assertEquals(id, message.getFromGroupId());
            assertEquals("root", message.getFromUser());
            assertEquals("群聊 🌍", message.getMessage());
        }
        QuitGroupRequestPacket quit = new QuitGroupRequestPacket();
        quit.setGroupId(id);
        carol.send(quit);
        QuitGroupResponsePacket quitted = carol.receive(QuitGroupResponsePacket.class);
        assertTrue(quitted.isSuccess());
        assertEquals(id, quitted.getGroupId());
        assertEquals(Set.of(a.getUserId(), b.getUserId()), members(alice, id));
        bob.send(new GroupMessageRequestPacket(id, "after quit"));
        assertEquals("after quit", alice.receive(GroupMessageResponsePacket.class).getMessage());
        assertEquals("after quit", bob.receive(GroupMessageResponsePacket.class).getMessage());
        carol.heartbeat();
    }

    @Test(timeout = 20000)
    public void logoutAcknowledgesThenRejectsUnauthenticatedBusinessRequest() throws Exception {
        TcpPeer peer = peer();
        String userId = login(peer, "root").getUserId();
        Channel serverChannel = SessionManager.getChannel(userId);
        peer.send(new LogoutRequestPacket());
        assertTrue(peer.receive(LogoutResponsePacket.class).isSuccess());
        assertNull(SessionManager.getChannel(userId));
        peer.send(new MessageRequestPacket("missing", "after logout"));
        peer.awaitClosed();
        server.awaitDisconnected(serverChannel);
    }

    @Test(timeout = 20000)
    public void disconnectRemovesSessionAndKeepsPersistentMembership() throws Exception {
        TcpPeer alice = peer();
        TcpPeer bob = peer();
        LoginResponsePacket a = login(alice, "root");
        LoginResponsePacket b = login(bob, "aaa");
        String id = createGroup(alice, bob, a.getUserId(), b.getUserId());
        Channel disconnected = SessionManager.getChannel(b.getUserId());
        bob.close();
        server.awaitDisconnected(disconnected);
        assertNull(SessionManager.getChannel(b.getUserId()));
        assertFalse(SessionManager.getChannelGroup(id).contains(disconnected));
        assertEquals(Set.of(a.getUserId(), b.getUserId()), members(alice, id));
        alice.send(new MessageRequestPacket(b.getUserId(), "offline"));
        alice.heartbeat();
    }

    @Test(timeout = 20000)
    public void supportsHeartbeatButRejectsBusinessTrafficBeforeLogin() throws Exception {
        TcpPeer peer = peer();
        peer.heartbeat();
        peer.send(new MessageRequestPacket("missing", "unauthenticated"));
        peer.awaitClosed();
    }

    @Test(timeout = 20000)
    public void acceptsHeaderFragmentsThatActuallyArriveInSeparateReads() throws Exception {
        TcpPeer peer = peer();
        LoginRequestPacket request = new LoginRequestPacket();
        request.setUsername("root");
        request.setPassword("root");
        byte[] bytes = TcpPeer.encode(request);
        peer.sendBytes(Arrays.copyOfRange(bytes, 0, 1));
        server.awaitInboundBytes(1);
        peer.sendBytes(Arrays.copyOfRange(bytes, 1, 3));
        server.awaitInboundBytes(2);
        peer.sendBytes(Arrays.copyOfRange(bytes, 3, bytes.length));
        LoginResponsePacket response = peer.receive(LoginResponsePacket.class);
        userIds.add(response.getUserId());
        assertTrue(response.isSuccess());
        peer.heartbeat();
    }

    @Test(timeout = 20000)
    public void processesCoalescedFramesAndRejectsUnknownAlgorithms() throws Exception {
        TcpPeer peer = peer();
        LoginRequestPacket login = new LoginRequestPacket();
        login.setUsername("root");
        login.setPassword("root");
        byte[] unknown = TcpPeer.encode(new HeartbeatRequestPacket());
        unknown[5] = 127;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(TcpPeer.encode(login));
        bytes.write(TcpPeer.encode(new HeartbeatRequestPacket()));
        peer.sendBytes(bytes.toByteArray());
        LoginResponsePacket response = peer.receive(LoginResponsePacket.class);
        userIds.add(response.getUserId());
        assertTrue(response.isSuccess());
        peer.receive(HeartbeatResponsePacket.class);
        peer.heartbeat();
        peer.sendBytes(unknown); peer.awaitClosed();
    }

    @Test(timeout = 20000)
    public void invalidMagicClosesRealConnection() throws Exception {
        TcpPeer peer = peer();
        peer.sendBytes(new byte[]{0, 0, 0, 0});
        peer.awaitClosed();
    }

    private TcpPeer peer() throws Exception {
        TcpPeer peer = new TcpPeer(server.port());
        peers.add(peer);
        return peer;
    }

    private LoginResponsePacket login(TcpPeer peer, String username) throws Exception {
        LoginResponsePacket response = peer.login(username);
        userIds.add(response.getUserId());
        return response;
    }

    private String createGroup(TcpPeer alice, TcpPeer bob, String aliceId, String bobId) throws Exception {
        CreateGroupRequestPacket create = new CreateGroupRequestPacket();
        create.setUserIds(List.of(aliceId, bobId));
        alice.send(create);
        CreateGroupResponsePacket response = alice.receive(CreateGroupResponsePacket.class);
        groupIds.add(response.getGroupId());
        assertTrue(response.isSuccess());
        assertEquals(List.of("root", "aaa"), response.getUsernameList());
        assertEquals(response, bob.receive(CreateGroupResponsePacket.class));
        return response.getGroupId();
    }

    private Set<String> members(TcpPeer peer, String id) throws Exception {
        ListGroupMemberRequestPacket request = new ListGroupMemberRequestPacket();
        request.setGroupId(id);
        peer.send(request);
        ListGroupMemberResponsePacket response = peer.receive(ListGroupMemberResponsePacket.class);
        assertEquals(id, response.getGroupId());
        return response.getSessions().stream().map(session -> session.getUserId()).collect(Collectors.toSet());
    }
}
