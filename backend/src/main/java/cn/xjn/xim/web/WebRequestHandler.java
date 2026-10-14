package cn.xjn.xim.web;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.util.SessionManager;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Validates typed binary WebSocket requests and routes them to shared business handlers. */
public class WebRequestHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    public static final int MAX_MESSAGE_LENGTH = 4000;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            cn.xjn.xim.server.limits.HandshakeDeadline.complete(ctx.channel());
            if (ctx.pipeline().get("handshake-timeout") != null) ctx.pipeline().remove("handshake-timeout");
            // Only upgraded WebSocket connections use the IM idle timeout.
            ctx.pipeline().addBefore(ctx.name(), "web-idle", new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
        } else if (event instanceof IdleStateEvent) {
            ctx.close();
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof BinaryWebSocketFrame binary)) {
            error(ctx, null, "仅支持 v2 Protobuf 二进制消息，请升级客户端");
            return;
        }
        String requestId = null;
        try {
            byte[] bytes = new byte[binary.content().readableBytes()];
            binary.content().getBytes(binary.content().readerIndex(), bytes);
            JSONObject json = cn.xjn.xim.protobuf.ProtocolWire.decodeWeb(bytes, true);
            require(json != null, "请求必须是 JSON 对象");
            Object id = json.get("requestId");
            require(id == null || id instanceof String && !((String) id).isBlank() && ((String) id).length() <= 80, "请求编号无效");
            requestId = (String) id;
            String type = string(json, "type", 30);
            if (!WebSessions.validate(ctx)) return;
            Packet packet;
            if (type.equals("resume")) {
                resume(ctx,json); return;
            }
            if (type.equals("heartbeat")) {
                ctx.fireChannelRead(new HeartbeatRequestPacket());
                return;
            }
            if (type.equals("login")) {
                require(!SessionManager.hasLogin(ctx.channel()), "请先退出当前账号");
                LoginRequestPacket login = new LoginRequestPacket();
                login.setRequestId(requestId);
                login.setUsername(credential(json, "username"));
                login.setPassword(credential(json, "password"));
                ctx.fireChannelRead(login);
                return;
            }
            if (type.equals("register")) {
                RegisterRequestPacket register = new RegisterRequestPacket();
                register.setRequestId(requestId);
                register.setUsername(credential(json, "username"));
                register.setPassword(credential(json, "password"));
                if (json.containsKey("displayName")) register.setDisplayName(string(json, "displayName", 64));
                ctx.fireChannelRead(register);
                return;
            }
            require(SessionManager.hasLogin(ctx.channel()), "请先登录");
            switch (type) {
                case "revokeSessions" -> {
                    cn.xjn.xim.storage.Storage.get().revokeSessions(SessionManager.getSession(ctx.channel()).getUserId());
                    SessionManager.invalidate(ctx.channel(),"revoked","所有登录凭证已撤销，请重新登录");
                    return;
                }
                case "changePassword" -> {
                    ChangePasswordRequestPacket change = new ChangePasswordRequestPacket();
                    change.setCurrentPassword(credential(json, "currentPassword"));
                    change.setNewPassword(credential(json, "newPassword"));
                    packet = change;
                }
                case "users" -> {
                    JSONObject response = event("users");
                    response.put("users", SessionManager.getOnlineSessions());
                    write(ctx, response);
                    return;
                }
                case "contacts" -> {
                    require(requestId != null, "联系人查询需要请求编号");
                    Object search = json.getOrDefault("query", "");
                    require(search instanceof String, "搜索词无效");
                    Object size = json.getOrDefault("limit", 20);
                    require(size instanceof Integer, "联系人页大小无效");
                    String after = json.containsKey("after") ? string(json, "after", 64) : null;
                    cn.xjn.xim.storage.ChatStore.Contacts page;
                    try {
                        page = cn.xjn.xim.storage.Storage.get().contacts(SessionManager.getSession(ctx.channel()).getUserId(),
                                (String) search, after, (Integer) size);
                    } catch (IllegalStateException unavailable) {
                        error(ctx, requestId, "联系人查询失败，请稍后重试"); return;
                    }
                    var online = SessionManager.getOnlineSessions().stream().map(cn.xjn.xim.session.Session::getUserId)
                            .collect(java.util.stream.Collectors.toSet());
                    var contacts = new JSONArray();
                    for (var user : page.users()) {
                        JSONObject contact = new JSONObject();
                        contact.put("userId", user.getUserId()); contact.put("username", user.getUsername());
                        contact.put("displayName", user.getDisplayName()); contact.put("online", online.contains(user.getUserId()));
                        contacts.add(contact);
                    }
                    JSONObject response = event("contacts"); response.put("requestId", requestId);
                    response.put("users", contacts); response.put("hasMore", page.hasMore()); response.put("nextAfter", page.nextAfter());
                    write(ctx, response); return;
                }
                case "conversations" -> packet = new ConversationsRequestPacket();
                case "read" -> {
                    ReadRequestPacket read = new ReadRequestPacket();
                    read.setKind(string(json, "kind", 10)); read.setId(string(json, "id", 64));
                    read.setSequence(string(json, "sequence", 30)); packet = read;
                }
                case "history" -> {
                    HistoryRequestPacket history = new HistoryRequestPacket();
                    history.setKind(string(json, "kind", 10)); history.setId(string(json, "id", 64));
                    if (json.containsKey("before")) history.setBefore(string(json, "before", 30));
                    if (json.containsKey("after")) history.setAfter(string(json, "after", 30));
                    if (json.containsKey("limit")) {
                        Object limit = json.get("limit");
                        require(limit instanceof Integer, "历史页大小无效"); history.setLimit((Integer) limit);
                    }
                    packet = history;
                }
                case "logout" -> packet = new LogoutRequestPacket();
                case "message" -> {
                    String toUserId = string(json, "toUserId", 64);
                    require(!toUserId.equals(SessionManager.getSession(ctx.channel()).getUserId()), "请选择其他用户");
                    packet = new MessageRequestPacket(toUserId, message(json));
                }
                case "createGroup" -> {
                    Object value = json.get("userIds");
                    require(value instanceof JSONArray, "请选择群组成员");
                    JSONArray users = (JSONArray) value;
                    require(users.size() <= 50, "群组最多选择 50 位用户");
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    // The shared handler adds the creator; this list contains selected partners only.
                    for (Object user : users) {
                        require(user instanceof String && ((String) user).length() <= 64, "成员编号无效");
                        ids.add((String) user);
                    }
                    CreateGroupRequestPacket create = new CreateGroupRequestPacket();
                    create.setUserIds(new ArrayList<>(ids));
                    packet = create;
                }
                case "joinGroup" -> {
                    JoinGroupRequestPacket join = new JoinGroupRequestPacket();
                    join.setGroupId(string(json, "groupId", 64));
                    packet = join;
                }
                case "members", "quitGroup", "groupMessage" -> {
                    String groupId = string(json, "groupId", 64);
                    if (type.equals("members")) {
                        ListGroupMemberRequestPacket members = new ListGroupMemberRequestPacket();
                        members.setGroupId(groupId);
                        packet = members;
                    } else if (type.equals("quitGroup")) {
                        QuitGroupRequestPacket quit = new QuitGroupRequestPacket();
                        quit.setGroupId(groupId);
                        packet = quit;
                    } else {
                        packet = new GroupMessageRequestPacket(groupId, message(json));
                    }
                }
                default -> throw new IllegalArgumentException("不支持的操作");
            }
            packet.setRequestId(requestId);
            ctx.fireChannelRead(packet);
        } catch (RuntimeException invalid) {
            error(ctx, requestId, invalid instanceof IllegalArgumentException ? invalid.getMessage() : "请求格式错误");
        }
    }

    private void resume(ChannelHandlerContext ctx, JSONObject json) {
        require(!SessionManager.hasLogin(ctx.channel()),"请先退出当前账号");
        var credential = cn.xjn.xim.storage.Storage.get().resumeSession(ctx.channel().attr(cn.xjn.xim.attribute.Attributes.COOKIE_TOKEN).get());
        if (credential == null) {
            JSONObject response = event("resume"); response.put("success",false);
            response.put("reason","登录凭证已过期、撤销或不存在，请重新登录"); write(ctx,response); return;
        }
        var previous = SessionManager.getChannel(credential.user().getUserId());
        Object connectionId = json.get("connectionId");
        if (previous != null && previous != ctx.channel() && previous.isActive()
                && !(credential.sessionId().equals(previous.attr(cn.xjn.xim.attribute.Attributes.SESSION_ID).get())
                     && connectionId instanceof String && connectionId.equals(previous.attr(cn.xjn.xim.attribute.Attributes.CONNECTION_ID).get()))) {
            JSONObject response = event("sessionInvalid"); response.put("code","replaced");
            response.put("reason","账号已在另一窗口连接，自动恢复已停止，请主动登录"); write(ctx,response); return;
        }
        SessionManager.bindSession(credential.user(),ctx.channel());
        WebSessions.bind(ctx,credential.sessionId(),credential.expiresAt());
        cn.xjn.xim.storage.Storage.get().conversations(credential.user().getUserId()).stream()
                .filter(chat -> chat.getKind().equals("group")).forEach(chat -> SessionManager.getChannelGroup(chat.getId()).add(ctx.channel()));
        JSONObject response = event("resume"); response.put("success",true);
        response.put("userId",credential.user().getUserId()); response.put("username",credential.user().getUsername());
        response.put("connectionId",ctx.channel().attr(cn.xjn.xim.attribute.Attributes.CONNECTION_ID).get());
        response.put("expiresAt",credential.expiresAt()); write(ctx,response);
    }

    private static String message(JSONObject json) {
        String message = string(json, "message", MAX_MESSAGE_LENGTH);
        require(!message.isBlank(), "消息不能为空");
        return message;
    }
    private static String credential(JSONObject json, String field) {
        Object value = json.get(field);
        require(value == null || value instanceof String, "账号或密码格式错误");
        return (String) value;
    }

    private static String string(JSONObject json, String field, int max) {
        Object value = json.get(field);
        require(value instanceof String && !((String) value).isBlank() && ((String) value).length() <= max,
                field + " 不能为空，且长度不能超过 " + max);
        return (String) value;
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }

    private static JSONObject event(String type) {
        JSONObject json = new JSONObject();
        json.put("type", type);
        return json;
    }

    private static void error(ChannelHandlerContext ctx, String requestId, String reason) {
        JSONObject json = event("error");
        json.put("requestId", requestId);
        json.put("reason", reason);
        write(ctx, json);
    }

    private static void write(ChannelHandlerContext ctx, JSONObject json) {
        ctx.writeAndFlush(cn.xjn.xim.protobuf.ProtocolWire.webFrame(json, false));
    }
}
