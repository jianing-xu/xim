package cn.xjn.xim.protocol.response;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import cn.xjn.xim.session.Session;
import lombok.Data;

import java.util.List;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Data
public class ListGroupMemberResponsePacket extends Packet {

    private String groupId;

    private List<Session> sessions;

    @Override
    public Byte getCommand() {
        return Command.LIST_GROUP_MEMBER_RESPONSE;
    }
}
