package cn.xjn.xim.web;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;
import java.util.Map;

public class WebPacketEncoder extends MessageToMessageEncoder<Packet> {
    private static final Map<Byte, String> TYPES = Map.ofEntries(
            Map.entry(Command.REGISTER_RESPONSE, "register"),
            Map.entry(Command.CHANGE_PASSWORD_RESPONSE, "changePassword"),
            Map.entry(Command.READ_RESPONSE, "read"),
            Map.entry(Command.LOGIN_RESPONSE, "login"),
            Map.entry(Command.LOGOUT_RESPONSE, "logout"),
            Map.entry(Command.MESSAGE_RESPONSE, "message"),
            Map.entry(Command.CREATE_GROUP_RESPONSE, "createGroup"),
            Map.entry(Command.JOIN_GROUP_RESPONSE, "joinGroup"),
            Map.entry(Command.QUIT_GROUP_RESPONSE, "quitGroup"),
            Map.entry(Command.LIST_GROUP_MEMBER_RESPONSE, "members"),
            Map.entry(Command.GROUP_MESSAGE_RESPONSE, "groupMessage"),
            Map.entry(Command.HEARTBEAT_RESPONSE, "heartbeat"),
            Map.entry(Command.HISTORY_RESPONSE, "history"),
            Map.entry(Command.CONVERSATIONS_RESPONSE, "conversations"),
            Map.entry(Command.ERROR_RESPONSE, "error"),
            Map.entry(Command.SENT_RESPONSE, "sent"));

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet packet, List<Object> out) {
        JSONObject json = (JSONObject) JSON.toJSON(packet);
        json.remove("command");
        json.remove("version");
        json.put("type", TYPES.get(packet.getCommand()));
        out.add(cn.xjn.xim.protobuf.ProtocolWire.webFrame(json, false));
    }
}
