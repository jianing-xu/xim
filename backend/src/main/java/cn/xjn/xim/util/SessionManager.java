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
        Channel previous = USER_ID_CHANNEL_MAP.put(session.getUserId(), channel);
        if (previous != null && previous != channel) {
            // Clear the old identity immediately; its queued requests must lose authorization.
            invalidate(previous, "replaced", "账号已在另一连接登录，请主动登录后继续");
        }
        channel.attr(Attributes.SESSION).set(session);
    }

    public static void unbindSession(Channel channel) {
        var expiry = channel.attr(Attributes.SESSION_EXPIRY).getAndSet(null);
        if (expiry != null) expiry.cancel(false);
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
    public static void invalidate(Channel channel, String code, String reason) {
        unbindSession(channel);
        if (Boolean.TRUE.equals(channel.attr(Attributes.WEB).get())) {
            var event = new com.alibaba.fastjson.JSONObject();
            event.put("type", "sessionInvalid"); event.put("code", code); event.put("reason", reason);
            channel.writeAndFlush(cn.xjn.xim.protobuf.ProtocolWire.webFrame(event, false))
                    .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        } else channel.close();
    }
    /** Password changes also increment ChatStore.credentialVersion, invalidating future resume credentials. */
    public static void revokeUserSessions(String userId) {
        Channel channel = getChannel(userId);
        if (channel != null) { unbindSession(channel); channel.close(); }
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
        if (!cn.xjn.xim.storage.Storage.get().hasGroup(groupId)) return null;
        ChannelGroup group = GROUP_ID_CHANNEL_GROUP_MAP.computeIfAbsent(groupId,
                key -> new io.netty.channel.group.DefaultChannelGroup(io.netty.util.concurrent.GlobalEventExecutor.INSTANCE));
        for (Session member : cn.xjn.xim.storage.Storage.get().members(groupId)) {
            Channel channel = getChannel(member.getUserId());
            if (channel != null && channel.isActive() && hasLogin(channel)) group.add(channel);
        }
        return group;
    }

    public static void unbindChannelGroup(String groupId) {
        GROUP_ID_CHANNEL_GROUP_MAP.remove(groupId);
    }
}
