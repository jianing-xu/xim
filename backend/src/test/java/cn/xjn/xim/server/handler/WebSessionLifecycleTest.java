package cn.xjn.xim.server.handler;

import cn.xjn.xim.attribute.Attributes;
import cn.xjn.xim.web.WebSessions;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSessionLifecycleTest {
    @Test public void repeatedBindingLogoutAndCloseCancelAbsoluteExpiryTasks() {
        var handler=new ChannelInboundHandlerAdapter();
        var channel=new EmbeddedChannel(handler,LoginRequestHandler.INSTANCE);
        var ctx=channel.pipeline().context(handler);
        try {
            SessionManager.bindSession(new Session("expiry-id","expiry"),channel);
            WebSessions.bind(ctx,"first",System.currentTimeMillis()+604800000);
            var first=channel.attr(Attributes.SESSION_EXPIRY).get();assertNotNull(first);
            WebSessions.bind(ctx,"second",System.currentTimeMillis()+604800000);
            assertTrue(first.isCancelled());var second=channel.attr(Attributes.SESSION_EXPIRY).get();
            SessionManager.unbindSession(channel);assertTrue(second.isCancelled());assertNull(channel.attr(Attributes.SESSION_EXPIRY).get());
            SessionManager.bindSession(new Session("expiry-id","expiry"),channel);
            WebSessions.bind(ctx,"third",System.currentTimeMillis()+604800000);var third=channel.attr(Attributes.SESSION_EXPIRY).get();
            channel.close();assertTrue(third.isCancelled());assertNull(channel.attr(Attributes.SESSION_EXPIRY).get());
        } finally {channel.finishAndReleaseAll();}
    }
}
