package cn.xjn.xim.integration;

import cn.xjn.xim.server.handler.IMServerChannelInitializer;
import cn.xjn.xim.codec.PacketCodecHandler;
import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

final class TcpTestServer implements AutoCloseable {
    private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
    private final NioEventLoopGroup worker = new NioEventLoopGroup(1);
    private final ChannelGroup connections = new DefaultChannelGroup(worker.next());
    private final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<Channel, CompletableFuture<Void>> inactiveEvents = new ConcurrentHashMap<>();
    private final BlockingQueue<Integer> inboundBytes = new LinkedBlockingQueue<>();
    private final AtomicInteger heartbeatCount = new AtomicInteger();
    private Channel listener;

    TcpTestServer() throws Exception {
        this(0);
    }

    TcpTestServer(int port) throws Exception {
        try {
            listener = new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new IMServerChannelInitializer() {
                        @Override
                        protected void initChannel(NioSocketChannel channel) throws Exception {
                            connections.add(channel);
                            CompletableFuture<Void> inactive = new CompletableFuture<>();
                            inactiveEvents.put(channel, inactive);
                            super.initChannel(channel);
                            channel.pipeline().addAfter(channel.pipeline().context(PacketCodecHandler.class).name(),
                                    "heartbeat-observer", new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                                            if (message instanceof HeartbeatRequestPacket) {
                                                heartbeatCount.incrementAndGet();
                                            }
                                            super.channelRead(ctx, message);
                                        }
                                    });
                            channel.pipeline().addFirst(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                                    if (message instanceof ByteBuf bytes) {
                                        inboundBytes.add(bytes.readableBytes());
                                    }
                                    super.channelRead(ctx, message);
                                }
                            });
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    super.channelInactive(ctx);
                                    inactive.complete(null);
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    errors.add(cause);
                                    ctx.close();
                                }
                            });
                        }
                    }).bind("127.0.0.1", port).sync().channel();
        } catch (Exception | Error failure) {
            close();
            throw failure;
        }
    }

    int port() {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    int heartbeatCount() {
        return heartbeatCount.get();
    }

    void awaitInboundBytes(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int received = 0;
        while (received < expected) {
            Integer bytes = inboundBytes.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull("Server did not receive the expected TCP fragment", bytes);
            received += bytes;
        }
    }

    void awaitDisconnected(Channel channel) throws Exception {
        assertTrue("Server did not detect peer disconnect", channel.closeFuture().await(5, TimeUnit.SECONDS));
        // A close future can complete before channelInactive; wait for the actual pipeline event.
        inactiveEvents.get(channel).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        try {
            if (listener != null) {
                assertTrue("Listener failed to close", listener.close().awaitUninterruptibly(5000));
            }
            assertTrue("Accepted connections failed to close", connections.close().awaitUninterruptibly(5000));
        } finally {
            var workerStopped = worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            var bossStopped = boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            assertTrue("Worker event loop leaked", workerStopped.awaitUninterruptibly(10000));
            assertTrue("Boss event loop leaked", bossStopped.awaitUninterruptibly(10000));
        }
        assertTrue("Unexpected server pipeline errors: " + errors, errors.isEmpty());
    }
}
