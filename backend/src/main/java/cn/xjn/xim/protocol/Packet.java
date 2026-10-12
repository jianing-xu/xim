package cn.xjn.xim.protocol;

import lombok.Data;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Data
public abstract class Packet {

    // protocol version
    private Byte version = 1;

    // command
    public abstract Byte getCommand();
}
