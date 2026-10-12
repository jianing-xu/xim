package cn.xjn.xim.util;

import java.util.UUID;

/**
 * @author xjn
 * @date 2023-12-27
 */
public class IDUtil {

    public static String genRandomId() {
        return UUID.randomUUID().toString().toLowerCase().split("-")[0];
    }
}
