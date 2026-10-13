package cn.xjn.xim.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Shared across both transports; bounded in-memory fixed windows for the single-instance MVP. */
public final class AttemptLimiter {
    public static final AttemptLimiter INSTANCE = new AttemptLimiter(System::currentTimeMillis);
    private static final long WINDOW = 5 * 60_000;
    private record Counter(long start, int count) {}
    private final Map<String, Counter> counters = new HashMap<>();
    private final LongSupplier clock;
    public AttemptLimiter(LongSupplier clock) { this.clock = clock; }
    public synchronized boolean allowed(String key, int limit) {
        long now = clock.getAsLong();
        counters.entrySet().removeIf(entry -> now - entry.getValue().start() >= WINDOW);
        Counter counter = counters.get(key);
        return (counter != null || counters.size() < 10_000) && (counter == null || counter.count() < limit);
    }
    public synchronized void record(String key) {
        if (!allowed(key, Integer.MAX_VALUE)) return;
        Counter previous = counters.get(key);
        counters.put(key, previous == null ? new Counter(clock.getAsLong(), 1) : new Counter(previous.start(), previous.count() + 1));
    }
    public synchronized void clear(String key) { counters.remove(key); }
}
