package cn.xjn.xim.serialize.impl;
import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.serialize.Serializer;
import cn.xjn.xim.serialize.SerializerAlgorithm;
public final class ProtobufSerializer implements Serializer {
    @Override public byte getSerializerAlgorithm() { return SerializerAlgorithm.PROTOCOL_BUFFER; }
    @Override public byte[] serialize(Object object) { return ProtocolWire.encodePacket((Packet)object); }
    @Override public <T> T deserialize(byte[] content, Class<T> clazz) { return ProtocolWire.decodePacket(content, clazz); }
}
