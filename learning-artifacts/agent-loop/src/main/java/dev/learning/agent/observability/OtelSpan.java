package dev.learning.agent.observability;

import dev.learning.agent.core.runtime.TraceSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.concurrent.atomic.AtomicBoolean;

/** A scope is closed on its owning thread; cancellation callbacks never close it. */
final class OtelSpan implements TraceSpan {
    final Span span;
    final long started = System.nanoTime();
    Long firstChunk;
    Long firstText;
    String tool;
    boolean sawUsage;
    private final Scope scope;
    private final boolean end;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean errored;

    OtelSpan(Span span, Scope scope, boolean end) {
        this.span = span;
        this.scope = scope;
        this.end = end;
    }

    public void attribute(String key, String value) {
        if (value != null) {
            span.setAttribute(key, value);
        }
    }

    public void attribute(String key, long value) {
        span.setAttribute(key, value);
    }

    public void attribute(String key, boolean value) {
        span.setAttribute(key, value);
    }

    public void event(String name, String code) {
        span.addEvent(name, Attributes.of(AttributeKey.stringKey("agent_loop.code"), code));
    }

    public void error(String code) {
        if (errored) {
            return;
        }
        errored = true;
        span.setAttribute("error.type", code);
        span.setAttribute("langfuse.observation.level", "ERROR");
        span.setStatus(StatusCode.ERROR, code);
    }

    public void fail(Throwable error) {
        if (error instanceof InterruptedException || error instanceof java.util.concurrent.CancellationException) {
            attribute("agent_loop.cancelled", true);
        } else if (error instanceof java.io.UncheckedIOException) {
            attribute("agent_loop.cancelled", true);
            attribute("agent_loop.cancel_reason", "CLIENT_DISCONNECTED");
        } else if (error instanceof java.util.concurrent.TimeoutException
            || error instanceof java.net.SocketTimeoutException
            || error instanceof java.net.http.HttpTimeoutException) {
            error("TIMEOUT");
        } else {
            error(error instanceof IllegalArgumentException ? "INVALID_ARGUMENTS" : error.getClass().getSimpleName());
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            scope.close();
        } finally {
            if (end) {
                span.end();
            }
        }
    }
}
