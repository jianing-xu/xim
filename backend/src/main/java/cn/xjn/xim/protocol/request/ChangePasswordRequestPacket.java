package cn.xjn.xim.protocol.request;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;

@lombok.Data
public class ChangePasswordRequestPacket extends Packet {
    @lombok.ToString.Exclude
    private String currentPassword;
    @lombok.ToString.Exclude
    private String newPassword;
    @Override public Byte getCommand() { return Command.CHANGE_PASSWORD_REQUEST; }
}
