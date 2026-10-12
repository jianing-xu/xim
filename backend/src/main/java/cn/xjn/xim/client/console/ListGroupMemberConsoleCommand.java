package cn.xjn.xim.client.console;

import cn.xjn.xim.protocol.request.ListGroupMemberRequestPacket;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class ListGroupMemberConsoleCommand implements ConsoleCommand {

    @Override
    public void exec(Scanner sc, Channel channel) {
        log.info("Please enter groupId to list members:");
        String groupId = sc.next();
        ListGroupMemberRequestPacket requestPacket = new ListGroupMemberRequestPacket();
        requestPacket.setGroupId(groupId);
        channel.writeAndFlush(requestPacket);
    }
}
