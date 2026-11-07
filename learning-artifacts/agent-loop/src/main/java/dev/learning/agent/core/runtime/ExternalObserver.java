package dev.learning.agent.core.runtime;

import java.net.URI;

/** Narrow port for external calls; implementations use only the currently activated run. */
public interface ExternalObserver {
    ExternalObserver NOOP = new ExternalObserver() {};
    default TraceSpan operation(String name) { return TraceSpan.NOOP; }
    default TraceSpan http(String method, URI endpoint) { return TraceSpan.NOOP; }
}
