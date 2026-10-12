package cn.xjn.xim.client.console;

import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.*;

public class ConsoleCommandTest extends ChannelTestSupport {
    @Test
    public void loginReadsUsernameAndPassword() {
        EmbeddedChannel channel = channel();
        try (Scanner scanner = new Scanner("root password")) {
            new LoginConsoleCommand().exec(scanner, channel);
        }
        LoginRequestPacket packet = channel.readOutbound();
        assertEquals("root", packet.getUsername());
        assertEquals("password", packet.getPassword());
    }

    @Test
    public void managerDispatchesEveryCommandWithParsedArguments() {
        EmbeddedChannel channel = channel();
        login(channel, "Alice");
        ConsoleCommandManager manager = new ConsoleCommandManager();
        execute(manager, channel, "sendToUser user-2 你好");
        MessageRequestPacket message = channel.readOutbound();
        assertEquals("user-2", message.getToUserId());
        assertEquals("你好", message.getMessage());
        execute(manager, channel, "createGroup user-1,user-2");
        CreateGroupRequestPacket create = channel.readOutbound();
        assertEquals(List.of("user-1", "user-2"), create.getUserIds());
        execute(manager, channel, "joinGroup group-1");
        assertEquals("group-1", ((JoinGroupRequestPacket) channel.readOutbound()).getGroupId());
        execute(manager, channel, "listGroupMember group-1");
        assertEquals("group-1", ((ListGroupMemberRequestPacket) channel.readOutbound()).getGroupId());
        execute(manager, channel, "quitGroup group-1");
        assertEquals("group-1", ((QuitGroupRequestPacket) channel.readOutbound()).getGroupId());
        execute(manager, channel, "sendToGroup group-1 hello");
        GroupMessageRequestPacket group = channel.readOutbound();
        assertEquals("group-1", group.getToGroupId());
        assertEquals("hello", group.getMessage());
        execute(manager, channel, "logout");
        assertTrue(channel.readOutbound() instanceof LogoutRequestPacket);
        assertNull(channel.readOutbound());
    }

    @Test
    public void unauthenticatedCommandDoesNotConsumeInputOrSendPacket() {
        EmbeddedChannel channel = channel();
        try (Scanner scanner = new Scanner("sendToUser user-2 hello")) {
            new ConsoleCommandManager().exec(scanner, channel);
            assertEquals("sendToUser", scanner.next());
        }
        assertNull(channel.readOutbound());
    }

    @Test
    public void unknownCommandDoesNotSendPacketOrCloseChannel() {
        EmbeddedChannel channel = channel();
        login(channel, "Alice");
        execute(new ConsoleCommandManager(), channel, "unknown");
        assertNull(channel.readOutbound());
        assertTrue(channel.isActive());
    }

    private void execute(ConsoleCommand command, EmbeddedChannel channel, String input) {
        try (Scanner scanner = new Scanner(input)) {
            command.exec(scanner, channel);
        }
    }
}
