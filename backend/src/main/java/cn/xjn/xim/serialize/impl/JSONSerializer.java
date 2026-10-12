package cn.xjn.xim.serialize.impl;

import cn.xjn.xim.serialize.Serializer;
import cn.xjn.xim.serialize.SerializerAlgorithm;
import com.alibaba.fastjson.JSON;

/**
 * @author xjn
 * @date 2023-12-26
 */
public class JSONSerializer implements Serializer {

    @Override
    public byte getSerializerAlgorithm() {
        return SerializerAlgorithm.JSON;
    }

    @Override
    public byte[] serialize(Object object) {
        return JSON.toJSONBytes(object);
    }

    @Override
    public <T> T deserialize(byte[] content, Class<T> clazz) {
        return JSON.parseObject(content, clazz);
    }
}
