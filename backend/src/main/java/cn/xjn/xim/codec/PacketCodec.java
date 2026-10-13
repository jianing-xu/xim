package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.serialize.Serializer;
import cn.xjn.xim.serialize.impl.JSONSerializer;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xjn
 * @date 2023-12-26
 */
public class PacketCodec {

    public static final PacketCodec INSTANCE = new PacketCodec();
    public static final int MAGIC_NUMBER = 0x12345678;
    public static final int HEADER_LENGTH = 11;

    private final Map<Byte, Class<? extends Packet>> packetTypeMap;
    private final Map<Byte, Serializer> serializerMap;

    private PacketCodec() {
        this.packetTypeMap = new HashMap<>();
        packetTypeMap.put(Command.READ_REQUEST, ReadRequestPacket.class);
        packetTypeMap.put(Command.READ_RESPONSE, ReadResponsePacket.class);
        packetTypeMap.put(Command.LOGIN_REQUEST, LoginRequestPacket.class);
        packetTypeMap.put(Command.LOGIN_RESPONSE, LoginResponsePacket.class);
        packetTypeMap.put(Command.MESSAGE_REQUEST, MessageRequestPacket.class);
        packetTypeMap.put(Command.MESSAGE_RESPONSE, MessageResponsePacket.class);
        packetTypeMap.put(Command.LOGOUT_REQUEST, LogoutRequestPacket.class);
        packetTypeMap.put(Command.LOGOUT_RESPONSE, LogoutResponsePacket.class);
        packetTypeMap.put(Command.CREATE_GROUP_REQUEST, CreateGroupRequestPacket.class);
        packetTypeMap.put(Command.CREATE_GROUP_RESPONSE, CreateGroupResponsePacket.class);
        packetTypeMap.put(Command.LIST_GROUP_MEMBER_REQUEST, ListGroupMemberRequestPacket.class);
        packetTypeMap.put(Command.LIST_GROUP_MEMBER_RESPONSE, ListGroupMemberResponsePacket.class);
        packetTypeMap.put(Command.JOIN_GROUP_REQUEST, JoinGroupRequestPacket.class);
        packetTypeMap.put(Command.JOIN_GROUP_RESPONSE, JoinGroupResponsePacket.class);
        packetTypeMap.put(Command.QUIT_GROUP_REQUEST, QuitGroupRequestPacket.class);
        packetTypeMap.put(Command.QUIT_GROUP_RESPONSE, QuitGroupResponsePacket.class);
        packetTypeMap.put(Command.GROUP_MESSAGE_REQUEST, GroupMessageRequestPacket.class);
        packetTypeMap.put(Command.GROUP_MESSAGE_RESPONSE, GroupMessageResponsePacket.class);
        packetTypeMap.put(Command.HEARTBEAT_REQUEST, HeartbeatRequestPacket.class);
        packetTypeMap.put(Command.HEARTBEAT_RESPONSE, HeartbeatResponsePacket.class);
        packetTypeMap.put(Command.HISTORY_REQUEST, HistoryRequestPacket.class);
        packetTypeMap.put(Command.CONVERSATIONS_REQUEST, ConversationsRequestPacket.class);
        packetTypeMap.put(Command.HISTORY_RESPONSE, HistoryResponsePacket.class);
        packetTypeMap.put(Command.CONVERSATIONS_RESPONSE, ConversationsResponsePacket.class);
        packetTypeMap.put(Command.ERROR_RESPONSE, ErrorResponsePacket.class);
        packetTypeMap.put(Command.SENT_RESPONSE, SentResponsePacket.class);
        packetTypeMap.put(Command.REGISTER_REQUEST, RegisterRequestPacket.class);
        packetTypeMap.put(Command.REGISTER_RESPONSE, RegisterResponsePacket.class);
        packetTypeMap.put(Command.CHANGE_PASSWORD_REQUEST, ChangePasswordRequestPacket.class);
        packetTypeMap.put(Command.CHANGE_PASSWORD_RESPONSE, ChangePasswordResponsePacket.class);
        this.serializerMap = new HashMap<>();
        JSONSerializer jsonSerializer = new JSONSerializer();
        serializerMap.put(jsonSerializer.getSerializerAlgorithm(), jsonSerializer);
    }

    public void encode(ByteBuf byteBuf, Packet packet) {
        // 1. serialize java object
        byte[] content = Serializer.DEFAULT.serialize(packet);

        // 2. the actual coding process
        byteBuf.writeInt(MAGIC_NUMBER);
        byteBuf.writeByte(packet.getVersion());
        byteBuf.writeByte(Serializer.DEFAULT.getSerializerAlgorithm());
        byteBuf.writeByte(packet.getCommand());
        byteBuf.writeInt(content.length);
        byteBuf.writeBytes(content);
    }

    public Packet decode(ByteBuf byteBuf) {
        if (byteBuf.readableBytes() < HEADER_LENGTH) {
            throw new CorruptedFrameException("Incomplete packet header");
        }
        if (byteBuf.readInt() != MAGIC_NUMBER) {
            throw new CorruptedFrameException("Invalid magic number");
        }
        byte version = byteBuf.readByte();
        // serialize algorithm
        byte serializeAlgorithm = byteBuf.readByte();
        // command
        byte command = byteBuf.readByte();
        // packet length
        int length = byteBuf.readInt();
        if (length < 0 || length > byteBuf.readableBytes()) {
            throw new CorruptedFrameException("Invalid packet length: " + length);
        }
        byte[] content = new byte[length];
        byteBuf.readBytes(content);

        Class<? extends Packet> requestType = getRequestType(command);
        Serializer serializer = getSerializer(serializeAlgorithm);

        if (requestType != null && serializer != null) {
            Packet packet;
            try { packet = serializer.deserialize(content, requestType); }
            catch (RuntimeException invalid) {
                // Parser exception messages can contain the original JSON, including passwords.
                throw new CorruptedFrameException("Invalid packet payload");
            }
            if (packet != null) {
                packet.setVersion(version);
            }
            return packet;
        }
        return null;
    }

    private Serializer getSerializer(byte serializeAlgorithm) {
        return this.serializerMap.get(serializeAlgorithm);
    }

    private Class<? extends Packet> getRequestType(byte command) {
        return packetTypeMap.get(command);
    }
}
