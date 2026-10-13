package cn.xjn.xim.support;

import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import org.junit.After;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class ChannelTestSupport {
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> groupIds = new ArrayList<>();

    protected EmbeddedChannel channel(ChannelHandler... handlers) {
        EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance(), handlers);
        channels.add(channel);
        return channel;
    }

    protected Session login(EmbeddedChannel channel, String username) {
        Session session = cn.xjn.xim.storage.Storage.get().user(username);
        SessionManager.bindSession(session, channel);
        return session;
    }

    protected String group(EmbeddedChannel... members) {
        String groupId = UUID.randomUUID().toString();
        ChannelGroup group = new DefaultChannelGroup(members[0].eventLoop());
        for (EmbeddedChannel member : members) {
            group.add(member);
        }
        cn.xjn.xim.storage.Storage.get().createGroup(groupId, java.util.Arrays.stream(members).map(SessionManager::getSession).map(Session::getUserId).toList());
        SessionManager.bindChannelGroup(groupId, group);
        trackGroup(groupId);
        return groupId;
    }

    protected void trackGroup(String groupId) {
        groupIds.add(groupId);
    }

    @After
    public void releaseChannelsAndSessions() {
        try {
            for (EmbeddedChannel channel : channels) {
                SessionManager.unbindSession(channel);
                channel.finishAndReleaseAll();
            }
        } finally {
            groupIds.forEach(SessionManager::unbindChannelGroup);
        }
    }
}
