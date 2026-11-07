package dev.learning.agent.observability;

import dev.learning.agent.core.runtime.TraceSpan;
import dev.learning.agent.telemetry.LangfuseAttributes;
import dev.learning.agent.telemetry.SpanAttributes;
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

    public void attributes(SpanAttributes attributes) {
        OtelAttributeWriter.write(span, attributes);
    }

    public void event(String name, String code) {
        span.addEvent(name, OtelAttributeWriter.attributes(new SpanAttributes.EventCode(code)));
    }

    public void error(String code) {
        if (errored) {
            return;
        }
        errored = true;
        attributes(new SpanAttributes.Error(code));
        attributes(new LangfuseAttributes.Level("ERROR"));
        span.setStatus(StatusCode.ERROR, code);
    }

    public void fail(Throwable error) {
        if (error instanceof InterruptedException || error instanceof java.util.concurrent.CancellationException) {
            attributes(new SpanAttributes.Cancellation(true, null));
        } else if (error instanceof java.io.UncheckedIOException) {
            attributes(new SpanAttributes.Cancellation(true, "CLIENT_DISCONNECTED"));
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
