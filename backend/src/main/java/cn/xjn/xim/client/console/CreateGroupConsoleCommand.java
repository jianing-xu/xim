package cn.xjn.xim.client.console;

import cn.xjn.xim.protocol.request.CreateGroupRequestPacket;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Scanner;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class CreateGroupConsoleCommand implements ConsoleCommand {

    @Override
    public void exec(Scanner sc, Channel channel) {
        log.info("Please enter userIds which split by comma:");
        String userIdStr = sc.next();
        CreateGroupRequestPacket requestPacket = new CreateGroupRequestPacket();
        requestPacket.setUserIds(Arrays.asList(userIdStr.split(",")));
        channel.writeAndFlush(requestPacket);
    }
}
