package cn.xjn.xim.integration;

import cn.xjn.xim.codec.PacketCodec;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.protocol.response.HeartbeatResponsePacket;
import cn.xjn.xim.protocol.response.LoginResponsePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.Assert.*;

// A wire client deliberately has no SessionManager/IMClientHandler state in the server JVM.
final class TcpPeer implements AutoCloseable {
    private final Socket socket = new Socket();
    private final DataInputStream input;

    TcpPeer(int port) throws IOException {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            input = new DataInputStream(socket.getInputStream());
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    LoginResponsePacket login(String username) throws IOException {
        LoginRequestPacket request = new LoginRequestPacket();
        request.setUsername(username);
        request.setPassword(username);
        send(request);
        LoginResponsePacket response = receive(LoginResponsePacket.class);
        assertTrue("Login failed: " + response.getReason(), response.isSuccess());
        assertEquals(username, response.getUsername());
        assertNotNull(response.getUserId());
        return response;
    }

    void heartbeat() throws IOException {
        send(new HeartbeatRequestPacket());
        receive(HeartbeatResponsePacket.class);
    }

    void send(Packet packet) throws IOException {
        sendBytes(encode(packet));
    }

    void sendBytes(byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    <T extends Packet> T receive(Class<T> expected) throws IOException {
        byte[] header = new byte[PacketCodec.HEADER_LENGTH];
        input.readFully(header);
        ByteBuf buffer = Unpooled.wrappedBuffer(header);
        int length;
        try {
            length = buffer.getInt(7);
        } finally {
            buffer.release();
        }
        if (length < 0 || length > 1024 * 1024) {
            throw new IOException("Unexpected response length: " + length);
        }
        byte[] content = new byte[length];
        input.readFully(content);
        buffer = Unpooled.buffer(header.length + length).writeBytes(header).writeBytes(content);
        try {
            Packet decoded = PacketCodec.INSTANCE.decode(buffer);
            assertNotNull("Expected " + expected.getSimpleName() + " but decoded no packet", decoded);
            assertEquals(expected, decoded.getClass());
            return expected.cast(decoded);
        } finally {
            buffer.release();
        }
    }

    void awaitClosed() throws IOException {
        assertEquals("Expected the server to close the TCP connection", -1, input.read());
    }

    static byte[] encode(Packet packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            PacketCodec.INSTANCE.encode(buffer, packet);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
