package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.serialize.Serializer;
import cn.xjn.xim.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class PacketCodecTest {
    @Parameterized.Parameters(name = "{index}: {0}")
    public static List<Packet> packets() {
        LoginRequestPacket login = new LoginRequestPacket();
        login.setUsername("用户");
        login.setPassword("password");
        LoginResponsePacket loginResponse = new LoginResponsePacket();
        loginResponse.setSuccess(true);
        loginResponse.setUserId("user-1");
        loginResponse.setUsername("用户");
        loginResponse.setReason("reason");
        LogoutResponsePacket logout = new LogoutResponsePacket();
        logout.setSuccess(true);
        CreateGroupRequestPacket create = new CreateGroupRequestPacket();
        create.setUserIds(List.of("user-1", "user-2"));
        CreateGroupResponsePacket created = new CreateGroupResponsePacket();
        created.setGroupId("group-1");
        created.setSuccess(true);
        created.setUsernameList(List.of("用户", "Bob"));
        created.setReason("reason");
        ListGroupMemberRequestPacket list = new ListGroupMemberRequestPacket();
        list.setGroupId("group-1");
        ListGroupMemberResponsePacket listed = new ListGroupMemberResponsePacket();
        listed.setGroupId("group-1");
        listed.setSessions(List.of(new Session("user-1", "用户"), new Session("user-2", "Bob")));
        JoinGroupRequestPacket join = new JoinGroupRequestPacket();
        join.setGroupId("group-1");
        JoinGroupResponsePacket joined = new JoinGroupResponsePacket();
        joined.setGroupId("group-1");
        joined.setSuccess(true);
        joined.setReason("reason");
        QuitGroupRequestPacket quit = new QuitGroupRequestPacket();
        quit.setGroupId("group-1");
        QuitGroupResponsePacket quitted = new QuitGroupResponsePacket();
        quitted.setGroupId("group-1");
        quitted.setSuccess(true);
        quitted.setReason("reason");
        GroupMessageResponsePacket groupMessage = new GroupMessageResponsePacket();
        groupMessage.setFromUser("用户");
        groupMessage.setFromGroupId("group-1");
        groupMessage.setMessage("你好 🌍");
        return List.of(login, loginResponse,
                new MessageRequestPacket("user-2", "你好 🌍"),
                new MessageResponsePacket("user-1", "用户", "你好 🌍"),
                new LogoutRequestPacket(), logout, create, created, list, listed, join, joined,
                quit, quitted, new GroupMessageRequestPacket("group-1", "你好 🌍"), groupMessage,
                new HeartbeatRequestPacket(), new HeartbeatResponsePacket());
    }

    private final Packet source;

    public PacketCodecTest(Packet source) {
        this.source = source;
    }

    @Test
    public void roundTripsEveryPacketTypeAndPayload() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            PacketCodec.INSTANCE.encode(buffer, source);
            Packet decoded = PacketCodec.INSTANCE.decode(buffer);
            assertEquals(source.getClass(), decoded.getClass());
            assertEquals(source.getVersion(), decoded.getVersion());
            assertEquals(source.getCommand(), decoded.getCommand());
            assertArrayEquals(Serializer.DEFAULT.serialize(source), Serializer.DEFAULT.serialize(decoded));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void writesProtocolHeaderAndUtf8Payload() {
        byte[] expected = Serializer.DEFAULT.serialize(source);
        ByteBuf buffer = Unpooled.buffer();
        try {
            PacketCodec.INSTANCE.encode(buffer, source);
            assertEquals(PacketCodec.HEADER_LENGTH + expected.length, buffer.readableBytes());
            assertEquals(PacketCodec.MAGIC_NUMBER, buffer.readInt());
            assertEquals(source.getVersion().byteValue(), buffer.readByte());
            assertEquals(Serializer.DEFAULT.getSerializerAlgorithm(), buffer.readByte());
            assertEquals(source.getCommand().byteValue(), buffer.readByte());
            assertEquals(expected.length, buffer.readInt());
            byte[] actual = new byte[buffer.readableBytes()];
            buffer.readBytes(actual);
            assertArrayEquals(expected, actual);
        } finally {
            buffer.release();
        }
    }
}
