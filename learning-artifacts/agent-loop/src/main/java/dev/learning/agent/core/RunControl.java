package dev.learning.agent.core;

import java.time.Duration;

public interface RunControl extends AutoCloseable {
    void check() throws InterruptedException;
    Duration remaining();
    AutoCloseable onCancel(Runnable action);
    void cancel(String reason);
    void close();
}
