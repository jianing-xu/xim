package cn.xjn.xim.util;

import cn.xjn.xim.session.Session;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class SessionManagerTest extends ChannelTestSupport {
    @Test
    public void bindsAndUnbindsSessionIdempotently() {
        EmbeddedChannel channel = channel();
        assertFalse(SessionManager.hasLogin(channel));
        assertNull(SessionManager.getSession(channel));
        Session session = login(channel, "Alice");
        assertTrue(SessionManager.hasLogin(channel));
        assertSame(session, SessionManager.getSession(channel));
        assertSame(channel, SessionManager.getChannel(session.getUserId()));
        SessionManager.unbindSession(channel);
        SessionManager.unbindSession(channel);
        assertFalse(SessionManager.hasLogin(channel));
        assertNull(SessionManager.getSession(channel));
        assertNull(SessionManager.getChannel(session.getUserId()));
    }

    @Test
    public void disconnectOfOldChannelKeepsNewChannelForSameUser() {
        EmbeddedChannel oldChannel = channel();
        EmbeddedChannel newChannel = channel();
        Session session = login(oldChannel, "Alice");
        SessionManager.bindSession(session, newChannel);
        SessionManager.unbindSession(oldChannel);
        assertSame(newChannel, SessionManager.getChannel(session.getUserId()));
        assertTrue(SessionManager.hasLogin(newChannel));
    }

    @Test
    public void rebindingChannelRemovesOldUserMapping() {
        EmbeddedChannel channel = channel();
        Session oldSession = login(channel, "Alice");
        Session newSession = login(channel, "Bob");
        assertNull(SessionManager.getChannel(oldSession.getUserId()));
        assertSame(channel, SessionManager.getChannel(newSession.getUserId()));
        assertSame(newSession, SessionManager.getSession(channel));
    }

    @Test
    public void rebindingSameIdentityKeepsGroupMembership() {
        EmbeddedChannel channel = channel();
        Session session = login(channel, "Alice");
        String id = group(channel);
        SessionManager.bindSession(new Session(session.getUserId(), "Alice"), channel);
        assertTrue(SessionManager.getChannelGroup(id).contains(channel));
        assertSame(channel, SessionManager.getChannel(session.getUserId()));
    }

    @Test
    public void logoutRemovesChannelFromAllGroups() {
        EmbeddedChannel member = channel();
        EmbeddedChannel other = channel();
        login(member, "Alice");
        login(other, "Bob");
        String first = group(member, other);
        String second = group(member);
        SessionManager.unbindSession(member);
        assertFalse(SessionManager.getChannelGroup(first).contains(member));
        assertTrue(SessionManager.getChannelGroup(first).contains(other));
        assertTrue(SessionManager.getChannelGroup(second).isEmpty());
    }

    @Test
    public void bindsAndRemovesGroup() {
        EmbeddedChannel member = channel();
        login(member, "Alice");
        String id = group(member);
        assertNotNull(SessionManager.getChannelGroup(id));
        SessionManager.unbindChannelGroup(id);
        SessionManager.unbindChannelGroup(id);
        assertTrue(SessionManager.getChannelGroup(id).contains(member));
        assertNull(SessionManager.getChannelGroup("missing"));
    }
}
