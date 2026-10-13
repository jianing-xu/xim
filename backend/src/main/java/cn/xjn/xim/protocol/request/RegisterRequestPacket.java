package cn.xjn.xim.protocol.request;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;

@lombok.Data
public class RegisterRequestPacket extends Packet {
    private String username;
    private String displayName;
    @lombok.ToString.Exclude
    private String password;
    @Override public Byte getCommand() { return Command.REGISTER_REQUEST; }
}
