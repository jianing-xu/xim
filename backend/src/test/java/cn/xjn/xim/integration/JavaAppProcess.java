package cn.xjn.xim.integration;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

final class JavaAppProcess implements AutoCloseable {
    private final Process process;
    private final Path logFile;
    private final Writer input;

    JavaAppProcess(Class<?> entrypoint, int port, int maxRetry) throws IOException {
        this(entrypoint, port, maxRetry, 0);
    }

    JavaAppProcess(Class<?> entrypoint, int port, int maxRetry, int webPort) throws IOException {
        this(entrypoint, port, maxRetry, webPort, null);
    }

    JavaAppProcess(Class<?> entrypoint, int port, int maxRetry, int webPort, Path database) throws IOException {
        this(entrypoint, port, maxRetry, webPort, database, true);
    }

    JavaAppProcess(Class<?> entrypoint, int port, int maxRetry, int webPort, Path database, Boolean demoEnabled) throws IOException {
        this(entrypoint,port,maxRetry,webPort,database,demoEnabled,new String[0]);
    }
    JavaAppProcess(Class<?> entrypoint, int port, int maxRetry, int webPort, Path database, Boolean demoEnabled, String... options) throws IOException {
        Path logs = Path.of("target", "integration-logs").toAbsolutePath();
        Files.createDirectories(logs);
        String processId = UUID.randomUUID().toString();
        logFile = logs.resolve(entrypoint.getSimpleName() + "-" + processId + ".log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dxim.host=127.0.0.1", "-Dxim.port=" + port, "-Dxim.web.port=" + webPort, "-Dxim.maxRetry=" + maxRetry,
                "-Dio.netty.eventLoopThreads=2", "-Dlogback.configurationFile=integration-logback.xml",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                entrypoint.getName()));
        command.addAll(1, List.of(options));
        command.add(1, "-Dxim.db.type=sqlite");
        if (demoEnabled != null) command.add(1, "-Dxim.auth.demo=" + demoEnabled);
        command.add(1, "-Dxim.db.sqlite.path=" + (database == null ? logs.resolve(processId + ".db") : database));
        // Each subprocess writes its own execution data; verify merges it with the test JVM data.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("org.jacoco.agent"))
                .findFirst().ifPresent(agent -> command.add(1, (agent.contains("=") ? agent.substring(0, agent.indexOf('=')) : agent)
                        + "=destfile=" + logs.getParent().resolve("jacoco-process-" + processId + ".exec")
                        + ",append=false"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile());
        if (demoEnabled == null) builder.environment().remove("XIM_AUTH_DEMO");
        process = builder.start();
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    String awaitLog(String text, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            String output = output();
            if (output.contains(text)) {
                return output;
            }
            if (!process.isAlive()) {
                output = output();
                if (output.contains(text)) {
                    return output;
                }
                fail("Process exited before logging '" + text + "'. Log: " + logFile + "\n" + output);
            }
            TimeUnit.MILLISECONDS.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out awaiting '" + text + "'. Log: " + logFile + "\n" + output());
    }

    void sendConsole(String line) throws IOException {
        input.write(line + "\n");
        input.flush();
    }

    int awaitExit(int seconds) throws InterruptedException, IOException {
        assertTrue("Process failed to exit. Log: " + logFile + "\n" + output(),
                process.waitFor(seconds, TimeUnit.SECONDS));
        return process.exitValue();
    }

    String output() throws IOException {
        return Files.readString(logFile, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                    fail("Process required forced termination. Log: " + logFile + "\n" + output());
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            input.close();
        }
    }
}
