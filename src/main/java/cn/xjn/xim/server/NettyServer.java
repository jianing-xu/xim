package cn.xjn.xim.server;

import cn.xjn.xim.server.handler.IMServerChannelInitializer;
import cn.xjn.xim.web.WebServerChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * @author xjn
 * @date 2023-12-20
 */
@Slf4j
public class NettyServer {

    private static final String HOST = System.getProperty("xim.host", "0.0.0.0");
    private static final int PORT = Integer.getInteger("xim.port", 8000);
    private static final int WEB_PORT = Integer.getInteger("xim.web.port", 8080);

    public static void main(String[] args) {
        NioEventLoopGroup boss = new NioEventLoopGroup();
        NioEventLoopGroup worker = new NioEventLoopGroup();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }, "xim-server-shutdown"));

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new IMServerChannelInitializer());

        bind(serverBootstrap, PORT).addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
                worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            } else if (WEB_PORT >= 0) {
                new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childHandler(new WebServerChannelInitializer())
                        .bind(HOST, WEB_PORT).addListener((ChannelFuture web) -> {
                            if (web.isSuccess()) {
                                log.info("Web chat started, port[{}], open http://127.0.0.1:{}/",
                                        ((InetSocketAddress) web.channel().localAddress()).getPort(),
                                        ((InetSocketAddress) web.channel().localAddress()).getPort());
                            } else {
                                log.error("Web chat bind port[{}] failed", WEB_PORT);
                                future.channel().close();
                                boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
                                worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
                            }
                        });
            }
        });
    }

    private static ChannelFuture bind(final ServerBootstrap serverBootstrap, final int port) {
        long begin = System.currentTimeMillis();
        return serverBootstrap.bind(HOST, port).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                log.info("The Netty server started up successfully, port[{}], cost {}ms.",
                        ((InetSocketAddress) future.channel().localAddress()).getPort(), System.currentTimeMillis() - begin);
            } else {
                log.error("The Netty server bind port[{}] failed", port);
            }
        });
    }
}
