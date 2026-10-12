package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.protocol.response.LoginResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.IDUtil;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Slf4j
@ChannelHandler.Sharable
public class LoginRequestHandler extends SimpleChannelInboundHandler<LoginRequestPacket> {

    public static final LoginRequestHandler INSTANCE = new LoginRequestHandler();

    private LoginRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LoginRequestPacket requestPacket) throws Exception {
        LoginResponsePacket responsePacket = new LoginResponsePacket();
        String username = requestPacket.getUsername();
        responsePacket.setUsername(username);

        if (auth(requestPacket)) {
            responsePacket.setSuccess(true);
            String userId = IDUtil.genRandomId();
            Session session = new Session(userId, username);
            responsePacket.setUserId(userId);
            SessionManager.bindSession(session, ctx.channel());
            log.info("[{}] login successful.", requestPacket.getUsername());
        } else {
            responsePacket.setSuccess(false);
            responsePacket.setReason("Authentication failed");
            log.info("login failed.");
        }

        ctx.channel().writeAndFlush(responsePacket);
    }

    private boolean auth(LoginRequestPacket requestPacket) {
        String username = requestPacket.getUsername();
        String password = requestPacket.getPassword();
        /* test code */
        Map<String, String> tempUserMap = new HashMap<>();
        tempUserMap.put("root", "root");
        tempUserMap.put("aaa", "aaa");
        tempUserMap.put("bbb", "bbb");
        tempUserMap.put("ccc", "ccc");
        String pwd = tempUserMap.get(username);
        return pwd != null && pwd.equals(password);
        /* test code */
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SessionManager.unbindSession(ctx.channel());
        super.channelInactive(ctx);
    }
}
