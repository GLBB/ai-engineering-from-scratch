package dev.learning.agent.model;

/** Reports meaningful output chunks, excluding transport keepalives and empty protocol events. */
@FunctionalInterface
public interface ModelStreamObserver {
    ModelStreamObserver NOOP = text -> {
    };

    void chunk(boolean visibleText);

    default void usage(TokenUsage usage, boolean finalUsage) {
    }
}
