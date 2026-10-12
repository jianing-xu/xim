package cn.xjn.xim.web;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.util.SessionManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Converts a small, validated JSON API into production IM packets. */
public class WebRequestHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    public static final int MAX_MESSAGE_LENGTH = 4000;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            // Only upgraded WebSocket connections use the IM idle timeout.
            ctx.pipeline().addBefore(ctx.name(), "web-idle", new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
        } else if (event instanceof IdleStateEvent) {
            ctx.close();
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame text)) {
            error(ctx, null, "仅支持 JSON 文本消息");
            return;
        }
        String requestId = null;
        try {
            JSONObject json = JSON.parseObject(text.text(), Feature.DisableSpecialKeyDetect);
            require(json != null, "请求必须是 JSON 对象");
            Object id = json.get("requestId");
            require(id == null || id instanceof String && ((String) id).length() <= 80, "请求编号无效");
            requestId = (String) id;
            String type = string(json, "type", 30);
            Packet packet;
            if (type.equals("heartbeat")) {
                ctx.fireChannelRead(new HeartbeatRequestPacket());
                return;
            }
            if (type.equals("login")) {
                require(!SessionManager.hasLogin(ctx.channel()), "请先退出当前账号");
                LoginRequestPacket login = new LoginRequestPacket();
                login.setUsername(string(json, "username", 64));
                login.setPassword(string(json, "password", 128));
                ctx.fireChannelRead(login);
                return;
            }
            require(SessionManager.hasLogin(ctx.channel()), "请先登录");
            switch (type) {
                case "users" -> {
                    JSONObject response = event("users");
                    response.put("users", SessionManager.getOnlineSessions());
                    write(ctx, response);
                    return;
                }
                case "logout" -> packet = new LogoutRequestPacket();
                case "message" -> {
                    String toUserId = string(json, "toUserId", 64);
                    require(!toUserId.equals(SessionManager.getSession(ctx.channel()).getUserId()), "请选择其他用户");
                    var receiver = SessionManager.getChannel(toUserId);
                    require(receiver != null && receiver.isActive() && SessionManager.hasLogin(receiver), "对方已离线，消息未发送");
                    packet = new MessageRequestPacket(toUserId, message(json));
                }
                case "createGroup" -> {
                    Object value = json.get("userIds");
                    require(value instanceof JSONArray, "请选择群组成员");
                    JSONArray users = (JSONArray) value;
                    require(users.size() <= 50, "群组最多选择 50 位用户");
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    ids.add(SessionManager.getSession(ctx.channel()).getUserId());
                    for (Object user : users) {
                        require(user instanceof String && ((String) user).length() <= 64, "成员编号无效");
                        var member = SessionManager.getChannel((String) user);
                        require(member != null && member.isActive() && SessionManager.hasLogin(member), "部分成员已离线，请刷新后重试");
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
                    var group = SessionManager.getChannelGroup(groupId);
                    require(group != null, "群组不存在");
                    require(group.contains(ctx.channel()), "请先加入该群组");
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
            ctx.fireChannelRead(packet);
            if (type.equals("message") || type.equals("groupMessage")) {
                JSONObject sent = event("sent");
                sent.put("requestId", requestId);
                write(ctx, sent);
            }
        } catch (RuntimeException invalid) {
            error(ctx, requestId, invalid instanceof IllegalArgumentException ? invalid.getMessage() : "请求格式错误");
        }
    }

    private static String message(JSONObject json) {
        String message = string(json, "message", MAX_MESSAGE_LENGTH);
        require(!message.isBlank(), "消息不能为空");
        return message;
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
        ctx.writeAndFlush(new TextWebSocketFrame(json.toJSONString()));
    }
}
