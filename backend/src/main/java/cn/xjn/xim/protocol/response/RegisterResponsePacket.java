package cn.xjn.xim.protocol.response;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;

@lombok.Data
public class RegisterResponsePacket extends Packet {
    private boolean success;
    private String reason;
    @Override public Byte getCommand() { return Command.REGISTER_RESPONSE; }
}
