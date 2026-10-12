package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.protocol.response.LoginResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class LoginRequestHandlerTest extends ChannelTestSupport {
    @Test
    public void authenticatesAllDemoAccountsAndBindsSessions() {
        for (String username : new String[]{"root", "aaa", "bbb", "ccc"}) {
            EmbeddedChannel channel = channel(LoginRequestHandler.INSTANCE);
            channel.writeInbound(request(username, username));
            LoginResponsePacket response = channel.readOutbound();
            assertTrue(response.isSuccess());
            assertEquals(username, response.getUsername());
            assertTrue(response.getUserId().matches("[0-9a-f]{8}"));
            assertNull(response.getReason());
            assertEquals(new Session(response.getUserId(), username), SessionManager.getSession(channel));
            assertSame(channel, SessionManager.getChannel(response.getUserId()));
        }
    }

    @Test
    public void rejectsWrongUnknownAndMissingCredentials() {
        String[][] credentials = {{"root", "wrong"}, {"missing", "missing"}, {"root", null},
                {null, "root"}, {null, null}, {"", ""}};
        for (String[] credential : credentials) {
            EmbeddedChannel channel = channel(LoginRequestHandler.INSTANCE);
            channel.writeInbound(request(credential[0], credential[1]));
            LoginResponsePacket response = channel.readOutbound();
            assertFalse(response.isSuccess());
            assertEquals("Authentication failed", response.getReason());
            assertNull(response.getUserId());
            assertFalse(SessionManager.hasLogin(channel));
        }
    }

    @Test
    public void disconnectUnbindsSessionAndPropagatesInactiveEvent() {
        AtomicBoolean inactive = new AtomicBoolean();
        EmbeddedChannel channel = channel(LoginRequestHandler.INSTANCE, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                inactive.set(true);
                super.channelInactive(ctx);
            }
        });
        channel.writeInbound(request("root", "root"));
        LoginResponsePacket response = channel.readOutbound();
        channel.close();
        assertTrue(inactive.get());
        assertNull(SessionManager.getChannel(response.getUserId()));
        assertFalse(SessionManager.hasLogin(channel));
    }

    private LoginRequestPacket request(String username, String password) {
        LoginRequestPacket packet = new LoginRequestPacket();
        packet.setUsername(username);
        packet.setPassword(password);
        return packet;
    }
}
