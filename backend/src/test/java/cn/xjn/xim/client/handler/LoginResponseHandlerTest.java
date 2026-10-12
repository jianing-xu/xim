package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.response.LoginResponsePacket;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class LoginResponseHandlerTest extends ChannelTestSupport {
    @Test
    public void successfulResponseBindsIdentity() {
        EmbeddedChannel channel = channel(new LoginResponseHandler());
        LoginResponsePacket packet = response(true);
        assertFalse(channel.writeInbound(packet));
        assertTrue(SessionManager.hasLogin(channel));
        assertEquals(packet.getUserId(), SessionManager.getSession(channel).getUserId());
        assertEquals("Alice", SessionManager.getSession(channel).getUsername());
        assertSame(channel, SessionManager.getChannel(packet.getUserId()));
        assertNull(channel.readOutbound());
    }

    @Test
    public void failedResponseDoesNotBindSession() {
        EmbeddedChannel channel = channel(new LoginResponseHandler());
        assertFalse(channel.writeInbound(response(false)));
        assertFalse(SessionManager.hasLogin(channel));
        assertNull(channel.readOutbound());
    }

    private LoginResponsePacket response(boolean success) {
        LoginResponsePacket packet = new LoginResponsePacket();
        packet.setUserId(java.util.UUID.randomUUID().toString());
        packet.setUsername("Alice");
        packet.setSuccess(success);
        packet.setReason("Authentication failed");
        return packet;
    }
}
