package cn.xjn.xim.storage;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.RejectedExecutionHandlers;

public final class Storage {
    private Storage() {}
    private static ChatStore store;
    // All business handlers share one ordered executor; JDBC never blocks socket event loops.
    public static final DefaultEventExecutorGroup EXECUTOR = new DefaultEventExecutorGroup(1,
            new DefaultThreadFactory("xim-storage", true), 1024, RejectedExecutionHandlers.reject());
    public static synchronized ChatStore get() {
        if (store == null) store = new ChatStore(DatabaseConfig.fromEnvironment());
        return store;
    }
    public static synchronized void close() { if (store != null) { store.close(); store = null; } }
}
