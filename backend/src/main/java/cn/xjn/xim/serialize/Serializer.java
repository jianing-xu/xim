package cn.xjn.xim.serialize;

import cn.xjn.xim.serialize.impl.JSONSerializer;

/**
 * @author xjn
 * @date 2023-12-26
 */
public interface Serializer {

    Serializer DEFAULT = new JSONSerializer();

    // get serializer algorithm
    byte getSerializerAlgorithm();

    // serialize java object
    byte[] serialize(Object object);

    // deserialize binary object
    <T> T deserialize(byte[] content, Class<T> clazz);
}
