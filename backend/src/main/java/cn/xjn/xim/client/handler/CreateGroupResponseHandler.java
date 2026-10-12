package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.response.CreateGroupResponsePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class CreateGroupResponseHandler extends SimpleChannelInboundHandler<CreateGroupResponsePacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, CreateGroupResponsePacket responsePacket) throws Exception {
        boolean success = responsePacket.isSuccess();
        if (success) {
            log.info("Create group success, groupId:[{}], there are [{}] in the group",
                    responsePacket.getGroupId(), responsePacket.getUsernameList());
        } else {
            log.info("Create group failed, the reason is: {}", responsePacket.getReason());
        }
    }
}
