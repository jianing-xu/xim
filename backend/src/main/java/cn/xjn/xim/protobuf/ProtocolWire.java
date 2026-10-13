package cn.xjn.xim.protobuf;

import cn.xjn.xim.protocol.Packet;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

/** Typed Protobuf is the wire format. JSON is only an in-process POJO adapter, never a wire blob. */
public final class ProtocolWire {
    public static final int VERSION = 2;
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().includingDefaultValueFields();
    private ProtocolWire() {}

    private static FieldDescriptor field(String type, boolean request) {
        String name = type + (request ? "Request" : "Response");
        return XimWire.Envelope.getDescriptor().getFields().stream()
                .filter(f -> f.getJsonName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported protocol operation"));
    }
    private static byte[] encode(JSONObject value, FieldDescriptor field) {
        try {
            var envelope = XimWire.Envelope.newBuilder().setVersion(VERSION);
            var payload = envelope.newBuilderForField(field);
            JsonFormat.parser().merge(value.toJSONString(), payload);
            envelope.setField(field, payload.build());
            return envelope.build().toByteArray();
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid Protobuf payload"); }
    }
    private static XimWire.Envelope parse(byte[] bytes) {
        try {
            var envelope = XimWire.Envelope.parseFrom(bytes);
            if (envelope.getVersion() != VERSION || envelope.getPayloadCase() == XimWire.Envelope.PayloadCase.PAYLOAD_NOT_SET)
                throw new IllegalArgumentException();
            return envelope;
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid Protobuf payload"); }
    }
    private static JSONObject body(XimWire.Envelope envelope, FieldDescriptor field) {
        try { return JSON.parseObject(PRINTER.print((Message) envelope.getField(field))); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid Protobuf payload"); }
    }
    public static byte[] encodeWeb(JSONObject event, boolean request) {
        JSONObject copy = new JSONObject(new java.util.HashMap<>(event)); String type = copy.getString("type"); copy.remove("type");
        return encode(copy, field(type, request));
    }
    public static JSONObject decodeWeb(byte[] bytes, boolean request) {
        var envelope = parse(bytes);
        var field = XimWire.Envelope.getDescriptor().findFieldByNumber(envelope.getPayloadCase().getNumber());
        String suffix = request ? "Request" : "Response", name = field.getJsonName();
        if (!name.endsWith(suffix)) throw new IllegalArgumentException("Invalid Protobuf direction");
        var result = body(envelope, field); result.put("type", name.substring(0, name.length() - suffix.length())); return result;
    }
    public static BinaryWebSocketFrame webFrame(JSONObject event, boolean request) {
        byte[] bytes = encodeWeb(event, request);
        if (bytes.length > 64 * 1024) throw new IllegalArgumentException("Protobuf frame too large");
        return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes));
    }
    public static int conversationSize(cn.xjn.xim.storage.ChatStore.Conversation conversation) {
        try {
            var builder=XimWire.Conversation.newBuilder();JsonFormat.parser().merge(JSON.toJSONString(conversation),builder);
            return builder.build().getSerializedSize();
        } catch(Exception failure) {throw new IllegalArgumentException("Invalid conversation summary");}
    }
    public static byte[] encodePacket(Packet packet) {
        var value = (JSONObject) JSON.toJSON(packet); value.remove("command"); value.remove("version");
        if (packet.getCommand() == 15 && value.containsKey("toGroupId")) value.put("groupId", value.remove("toGroupId"));
        var field = XimWire.Envelope.getDescriptor().findFieldByNumber(packet.getCommand());
        if (field == null) throw new IllegalArgumentException("Unsupported packet");
        return encode(value, field);
    }
    public static <T> T decodePacket(byte[] bytes, Class<T> clazz) {
        try {
            var envelope = parse(bytes);
            var expected = (Packet) clazz.getDeclaredConstructor().newInstance();
            if (envelope.getPayloadCase().getNumber() != expected.getCommand()) throw new IllegalArgumentException();
            var field = XimWire.Envelope.getDescriptor().findFieldByNumber(expected.getCommand());
            var value = body(envelope, field);
            if (expected.getCommand() == 15 && value.containsKey("groupId")) value.put("toGroupId", value.remove("groupId"));
            return value.toJavaObject(clazz);
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid Protobuf payload"); }
    }
}
