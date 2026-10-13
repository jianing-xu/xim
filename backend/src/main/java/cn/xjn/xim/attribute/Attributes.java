package cn.xjn.xim.attribute;

import cn.xjn.xim.session.Session;
import io.netty.util.AttributeKey;

/**
 * @author xjn
 * @date 2023-12-27
 */
public interface Attributes {

    AttributeKey<Session> SESSION = AttributeKey.newInstance("session");
    AttributeKey<Boolean> WEB = AttributeKey.valueOf("xim.web");
    AttributeKey<String> COOKIE_TOKEN = AttributeKey.valueOf("xim.cookie");
    AttributeKey<String> SESSION_ID = AttributeKey.valueOf("xim.credential");
    AttributeKey<String> CONNECTION_ID = AttributeKey.valueOf("xim.connection");
    AttributeKey<io.netty.util.concurrent.ScheduledFuture<?>> SESSION_EXPIRY = AttributeKey.valueOf("xim.expiry");
}
