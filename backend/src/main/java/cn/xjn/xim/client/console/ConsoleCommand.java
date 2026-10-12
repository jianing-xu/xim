package cn.xjn.xim.client.console;

import io.netty.channel.Channel;

import java.util.Scanner;

/**
 * @author xjn
 * @date 2023-12-27
 */
public interface ConsoleCommand {

    void exec(Scanner sc, Channel channel);
}
