package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import cn.xjn.xim.protocol.request.LogoutRequestPacket;
import cn.xjn.xim.protocol.response.HeartbeatResponsePacket;
import cn.xjn.xim.protocol.response.LogoutResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class LogoutAndHeartbeatRequestHandlerTest extends ChannelTestSupport {
    @Test
    public void logoutUnbindsSessionAndAcknowledgesRepeatRequests() {
        EmbeddedChannel channel = channel(LogoutRequestHandler.INSTANCE);
        Session session = login(channel, "Alice");
        for (int i = 0; i < 2; i++) {
            channel.writeInbound(new LogoutRequestPacket());
            assertTrue(((LogoutResponsePacket) channel.readOutbound()).isSuccess());
            assertFalse(SessionManager.hasLogin(channel));
            assertNull(SessionManager.getChannel(session.getUserId()));
        }
    }

    @Test
    public void answersHeartbeatBeforeAuthentication() {
        EmbeddedChannel channel = channel(HeartbeatRequestHandler.INSTANCE, AuthHandler.INSTANCE);
        assertFalse(channel.writeInbound(new HeartbeatRequestPacket()));
        assertTrue(channel.readOutbound() instanceof HeartbeatResponsePacket);
        assertTrue(channel.isActive());
    }
}
