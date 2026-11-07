package dev.learning.agent.core.runtime;

import dev.learning.agent.telemetry.SpanAttributes;

/** A lexical operation scope. Implementations restore the previous context on close. */
public interface TraceSpan extends AutoCloseable {
    TraceSpan NOOP = new TraceSpan() {};

    default void attributes(SpanAttributes attributes) {
    }

    default void event(String name, String code) {
    }

    default void fail(Throwable error) {
    }

    default void error(String code) {
    }

    @Override
    default void close() {
    }
}
