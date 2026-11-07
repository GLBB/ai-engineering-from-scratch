package dev.learning.agent.core.runtime;

/** A lexical operation scope. Implementations restore the previous context on close. */
public interface TraceSpan extends AutoCloseable {
    TraceSpan NOOP = new TraceSpan() {};
    default void attribute(String name, String value) {}
    default void attribute(String name, long value) {}
    default void attribute(String name, boolean value) {}
    default void event(String name, String code) {}
    default void fail(Throwable error) {}
    default void error(String code) {}
    @Override default void close() {}
}
