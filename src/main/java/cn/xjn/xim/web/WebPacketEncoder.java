package cn.xjn.xim.web;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;
import java.util.Map;

public class WebPacketEncoder extends MessageToMessageEncoder<Packet> {
    private static final Map<Byte, String> TYPES = Map.of(
            Command.LOGIN_RESPONSE, "login", Command.LOGOUT_RESPONSE, "logout",
            Command.MESSAGE_RESPONSE, "message", Command.CREATE_GROUP_RESPONSE, "createGroup",
            Command.JOIN_GROUP_RESPONSE, "joinGroup", Command.QUIT_GROUP_RESPONSE, "quitGroup",
            Command.LIST_GROUP_MEMBER_RESPONSE, "members", Command.GROUP_MESSAGE_RESPONSE, "groupMessage",
            Command.HEARTBEAT_RESPONSE, "heartbeat");

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet packet, List<Object> out) {
        JSONObject json = (JSONObject) JSON.toJSON(packet);
        json.remove("command");
        json.remove("version");
        json.put("type", TYPES.get(packet.getCommand()));
        out.add(new TextWebSocketFrame(json.toJSONString()));
    }
}
