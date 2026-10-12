package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.response.ListGroupMemberResponsePacket;
import cn.xjn.xim.session.Session;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class ListGroupMemberResponseHandler extends SimpleChannelInboundHandler<ListGroupMemberResponsePacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ListGroupMemberResponsePacket responsePacket) throws Exception {
        log.info("There are {} in the group[{}]",
                responsePacket.getSessions().stream().map(Session::getUsername).collect(Collectors.toList()), responsePacket.getGroupId());
    }
}
