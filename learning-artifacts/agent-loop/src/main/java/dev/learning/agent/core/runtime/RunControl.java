package dev.learning.agent.core.runtime;

import java.time.Duration;

public interface RunControl extends AutoCloseable {
    void check() throws InterruptedException;
    Duration remaining();
    AutoCloseable onCancel(Runnable action);
    default boolean cancelled() { return false; }
    void cancel(String reason);
    void close();
}
