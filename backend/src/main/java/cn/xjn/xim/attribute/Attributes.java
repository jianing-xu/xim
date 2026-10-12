package cn.xjn.xim.attribute;

import cn.xjn.xim.session.Session;
import io.netty.util.AttributeKey;

/**
 * @author xjn
 * @date 2023-12-27
 */
public interface Attributes {

    AttributeKey<Session> SESSION = AttributeKey.newInstance("session");
}
