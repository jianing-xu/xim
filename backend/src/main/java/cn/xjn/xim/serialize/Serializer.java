package cn.xjn.xim.serialize;


/**
 * @author xjn
 * @date 2023-12-26
 */
public interface Serializer {

    Serializer DEFAULT = new cn.xjn.xim.serialize.impl.ProtobufSerializer();

    // get serializer algorithm
    byte getSerializerAlgorithm();

    // serialize java object
    byte[] serialize(Object object);

    // deserialize binary object
    <T> T deserialize(byte[] content, Class<T> clazz);
}
