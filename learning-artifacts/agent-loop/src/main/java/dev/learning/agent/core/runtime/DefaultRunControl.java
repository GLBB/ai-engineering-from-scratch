package dev.learning.agent.core.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Owns cancellation actions; closing a registration only unregisters it. */
public final class DefaultRunControl implements RunControl {
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "agent-deadlines");
                thread.setDaemon(true);
                return thread;
            });
    private final LongSupplier clock;
    private final long deadline;
    private final Map<Object, Runnable> actions = new LinkedHashMap<>();
    private final ScheduledFuture<?> timeout;
    private String reason;
    private boolean closed;

    public DefaultRunControl(Duration duration) {
        this(duration, System::nanoTime);
    }

    DefaultRunControl(Duration duration, LongSupplier clock) {
        if (duration.isNegative()) throw new IllegalArgumentException("negative deadline");
        this.clock = clock;
        deadline = clock.getAsLong() + duration.toNanos();
        timeout = TIMER.schedule(() -> cancel("run deadline exceeded"), duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void check() throws InterruptedException {
        if (remaining().isZero()) cancel("run deadline exceeded");
        synchronized (this) {
            if (reason != null) throw new InterruptedException(reason);
            if (closed) throw new InterruptedException("run is closed");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("run interrupted");
    }

    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, deadline - clock.getAsLong()));
    }

    public AutoCloseable onCancel(Runnable action) {
        Object key = new Object();
        boolean invoke;
        synchronized (this) {
            invoke = reason != null;
            if (!invoke && !closed) actions.put(key, action);
        }
        if (invoke) runSafely(action);
        return () -> { synchronized (this) { actions.remove(key); } };
    }

    public void cancel(String cause) {
        ArrayList<Runnable> pending;
        synchronized (this) {
            if (reason != null || closed) return;
            reason = cause;
            pending = new ArrayList<>(actions.values());
            actions.clear();
        }
        pending.forEach(DefaultRunControl::runSafely);
    }

    private static void runSafely(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { /* Continue closing other resources. */ }
    }

    public synchronized boolean cancelled() { return reason != null; }

    public synchronized void close() {
        closed = true;
        actions.clear();
        timeout.cancel(false);
    }
}
