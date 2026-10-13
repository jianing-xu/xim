package cn.xjn.xim.integration;

import cn.xjn.xim.server.NettyServer;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Actual default production configuration in two independent JVMs and a real SQLite file. */
public class AccountProductionIT {
    @Test(timeout = 40000) public void productionRejectsDemoCredentialsAndRecoversChangedPasswordAndHistory() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "production-accounts-");
        Path database = directory.resolve("chat.db");
        String id, peerId, groupId;
        try {
            try (var server = new JavaAppProcess(NettyServer.class, 0, 1, -1, database, null)) {
                int port = port(server.awaitLog("started up successfully", 10));
                try (var user = new TcpPeer(port); var peer = new TcpPeer(port)) {
                    assertFalse(auth(user, "root", "root").isSuccess());
                    register(user, "production_user", "first-private-password");
                    register(peer, "production_peer", "peer-private-password");
                    var logged = auth(user, "production_user", "first-private-password"); assertTrue(logged.isSuccess()); id = logged.getUserId();
                    peerId = auth(peer, "production_peer", "peer-private-password").getUserId(); assertNotNull(peerId);
                    var create = new CreateGroupRequestPacket(); create.setUserIds(List.of(id, peerId)); user.send(create);
                    groupId = user.receive(CreateGroupResponsePacket.class).getGroupId(); peer.receive(CreateGroupResponsePacket.class);
                    var message = new MessageRequestPacket(peerId, "retained account history"); message.setRequestId("account-message"); user.send(message);
                    assertNotNull(user.receive(SentResponsePacket.class).getId()); peer.receive(MessageResponsePacket.class);
                    var change = new ChangePasswordRequestPacket(); change.setCurrentPassword("first-private-password"); change.setNewPassword("second-private-password");
                    user.send(change); assertTrue(user.receive(ChangePasswordResponsePacket.class).isSuccess()); user.awaitClosed();
                }
                assertNoCredentials(server.output());
            }
            try (var server = new JavaAppProcess(NettyServer.class, 0, 1, -1, database, null)) {
                int port = port(server.awaitLog("started up successfully", 10));
                try (var user = new TcpPeer(port)) {
                    assertFalse(auth(user, "root", "root").isSuccess());
                    assertFalse(auth(user, "production_user", "first-private-password").isSuccess());
                    var logged = auth(user, "production_user", "second-private-password"); assertTrue(logged.isSuccess()); assertEquals(id, logged.getUserId());
                    var history = new HistoryRequestPacket(); history.setKind("user"); history.setId(peerId); user.send(history);
                    assertEquals("retained account history", user.receive(HistoryResponsePacket.class).getMessages().getFirst().getMessage());
                    var members = new ListGroupMemberRequestPacket(); members.setGroupId(groupId); user.send(members);
                    assertEquals(Set.of(id, peerId), user.receive(ListGroupMemberResponsePacket.class).getSessions().stream().map(cn.xjn.xim.session.Session::getUserId).collect(java.util.stream.Collectors.toSet()));
                }
                assertNoCredentials(server.output());
            }
        } finally { try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static LoginResponsePacket auth(TcpPeer peer, String username, String password) throws Exception {
        var login = new LoginRequestPacket(); login.setUsername(username); login.setPassword(password); peer.send(login);
        return peer.receive(LoginResponsePacket.class);
    }
    private static void register(TcpPeer peer, String username, String password) throws Exception {
        var request = new RegisterRequestPacket(); request.setUsername(username); request.setPassword(password); peer.send(request);
        assertTrue(peer.receive(RegisterResponsePacket.class).isSuccess());
    }
    private static void assertNoCredentials(String log) {
        for (String secret : List.of("first-private-password", "second-private-password", "peer-private-password", "pbkdf2-sha256$")) assertFalse(log.contains(secret));
    }
    private static int port(String output) {
        var matcher = Pattern.compile("port\\[(\\d+)]").matcher(output); assertTrue(matcher.find()); return Integer.parseInt(matcher.group(1));
    }
}
