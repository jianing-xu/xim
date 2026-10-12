package cn.xjn.xim.integration;

import cn.xjn.xim.client.NettyClient;
import cn.xjn.xim.server.NettyServer;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ApplicationLifecycleIT {
    @Test(timeout = 30000)
    public void startsServerAndClientEntrypointsAndRunsConsoleLoginAndExit() throws Exception {
        try (JavaAppProcess server = new JavaAppProcess(NettyServer.class, 0, 1)) {
            String output = server.awaitLog("started up successfully", 10);
            Matcher port = Pattern.compile("port\\[(\\d+)]").matcher(output);
            assertTrue("Startup log did not include bound port: " + output, port.find());
            Matcher webPort = Pattern.compile("Web chat started, port\\[(\\d+)]").matcher(server.awaitLog("Web chat started", 5));
            assertTrue(webPort.find());
            try (HttpClient http = HttpClient.newHttpClient()) {
                var page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort.group(1)))
                        .timeout(java.time.Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode());
                assertTrue(page.body().contains("登录，开始聊天"));
            }
            try (JavaAppProcess client = new JavaAppProcess(NettyClient.class, Integer.parseInt(port.group(1)), 1)) {
                client.awaitLog("successfully connected", 10);
                client.sendConsole("root root");
                client.awaitLog("login successful", 10);
                client.awaitLog("Please enter command", 5);
                client.sendConsole("exit");
                // The existing console exit command deliberately calls System.exit(1).
                assertEquals(1, client.awaitExit(10));
            }
        }
    }

    @Test(timeout = 30000)
    public void retriesRealRefusedConnectionAndSucceedsAfterServerStarts() throws Exception {
        int port = unusedPort();
        try (JavaAppProcess client = new JavaAppProcess(NettyClient.class, port, 2)) {
            client.awaitLog("make a 1 attempt to reconnect after 2 seconds", 10);
            try (TcpTestServer server = new TcpTestServer(port)) {
                client.awaitLog("successfully connected", 10);
                client.sendConsole("root root");
                client.awaitLog("login successful", 10);
                client.awaitLog("Please enter command", 5);
                assertFalse(client.output().contains("retries has been exhausted"));
                client.sendConsole("exit");
                assertEquals(1, client.awaitExit(10));
            }
        }
    }

    @Test(timeout = 20000)
    public void exhaustedRetryBudgetStopsClientEventLoopsAndExits() throws Exception {
        try (JavaAppProcess client = new JavaAppProcess(NettyClient.class, unusedPort(), 1)) {
            client.awaitLog("make a 1 attempt to reconnect after 2 seconds", 10);
            client.awaitLog("retries has been exhausted", 10);
            assertEquals(0, client.awaitExit(5));
            assertFalse(client.output().contains("successfully connected"));
            assertEquals(1, client.output().lines().filter(line -> line.contains("attempt to reconnect")).count());
        }
    }

    @Test(timeout = 20000)
    public void bindFailureStopsServerEventLoopsAndExits() throws Exception {
        try (TcpTestServer listener = new TcpTestServer();
             JavaAppProcess server = new JavaAppProcess(NettyServer.class, listener.port(), 1)) {
            server.awaitLog("bind port[" + listener.port() + "] failed", 10);
            server.awaitExit(5);
            assertFalse(server.output().contains("started up successfully"));
        }
    }

    @Test(timeout = 20000)
    public void webBindFailureClosesTcpListenerAndStopsServer() throws Exception {
        try (TcpTestServer occupied = new TcpTestServer();
             JavaAppProcess server = new JavaAppProcess(NettyServer.class, 0, 1, occupied.port())) {
            server.awaitLog("Web chat bind port[" + occupied.port() + "] failed", 10);
            server.awaitExit(5);
        }
    }

    @Test(timeout = 35000)
    public void realClientHeartbeatsKeepConnectionAliveBeyondReadIdleTimeout() throws Exception {
        try (TcpTestServer server = new TcpTestServer();
             JavaAppProcess client = new JavaAppProcess(NettyClient.class, server.port(), 1)) {
            client.awaitLog("successfully connected", 10);
            client.sendConsole("root root");
            String output = client.awaitLog("login successful", 10);
            Matcher user = Pattern.compile("\\[([0-9a-f]{8}):root] login successful").matcher(output);
            assertTrue("Client login log did not include identity: " + output, user.find());
            Channel serverChannel = SessionManager.getChannel(user.group(1));
            assertNotNull(serverChannel);
            assertFalse("Read idle closed the real client despite its periodic heartbeats",
                    serverChannel.closeFuture().await(17, TimeUnit.SECONDS));
            assertTrue("Expected at least three heartbeats from the production client", server.heartbeatCount() >= 3);
            client.awaitLog("Please enter command", 5);
            client.sendConsole("exit");
            assertEquals(1, client.awaitExit(5));
            server.awaitDisconnected(serverChannel);
            assertNull(SessionManager.getChannel(user.group(1)));
        }
    }

    private int unusedPort() throws Exception {
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return reservation.getLocalPort();
        }
    }
}
