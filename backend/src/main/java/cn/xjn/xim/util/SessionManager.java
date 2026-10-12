package cn.xjn.xim.util;

import cn.xjn.xim.attribute.Attributes;
import cn.xjn.xim.session.Session;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Slf4j
public class SessionManager {

    private static final Map<String, Channel> USER_ID_CHANNEL_MAP = new ConcurrentHashMap<>();
    private static final Map<String, ChannelGroup> GROUP_ID_CHANNEL_GROUP_MAP = new ConcurrentHashMap<>();

    public static void bindSession(Session session, Channel channel) {
        Session previousSession = getSession(channel);
        if (previousSession != null && !previousSession.getUserId().equals(session.getUserId())) {
            unbindSession(channel);
        }
        USER_ID_CHANNEL_MAP.put(session.getUserId(), channel);
        channel.attr(Attributes.SESSION).set(session);
    }

    public static void unbindSession(Channel channel) {
        if (hasLogin(channel)) {
            Session session = getSession(channel);
            USER_ID_CHANNEL_MAP.remove(session.getUserId(), channel);
            channel.attr(Attributes.SESSION).set(null);
            GROUP_ID_CHANNEL_GROUP_MAP.values().forEach(group -> group.remove(channel));
            log.info("[{}] logout.", session.getUsername());
        }
    }

    public static Session getSession(Channel channel) {
        return channel.attr(Attributes.SESSION).get();
    }

    public static Channel getChannel(String userId) {
        return USER_ID_CHANNEL_MAP.get(userId);
    }

    public static boolean hasLogin(Channel channel) {
        return getSession(channel) != null;
    }

    public static List<Session> getOnlineSessions() {
        return USER_ID_CHANNEL_MAP.values().stream()
                .filter(Channel::isActive).map(SessionManager::getSession)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Session::getUsername).thenComparing(Session::getUserId))
                .toList();
    }

    public static void bindChannelGroup(String groupId, ChannelGroup channelGroup) {
        GROUP_ID_CHANNEL_GROUP_MAP.put(groupId, channelGroup);
    }

    public static ChannelGroup getChannelGroup(String groupId) {
        return GROUP_ID_CHANNEL_GROUP_MAP.get(groupId);
    }

    public static void unbindChannelGroup(String groupId) {
        GROUP_ID_CHANNEL_GROUP_MAP.remove(groupId);
    }
}
