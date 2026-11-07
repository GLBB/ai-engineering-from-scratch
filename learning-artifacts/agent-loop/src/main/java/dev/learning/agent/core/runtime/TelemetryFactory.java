package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;

public interface TelemetryFactory extends AutoCloseable {
    TelemetryFactory NOOP = (session, request, mode, protocol) -> RunTelemetry.NOOP;

    RunTelemetry open(String session, ExecutionRequest request, AgentMode mode, String protocol);

    default ExternalObserver external() {
        return ExternalObserver.NOOP;
    }

    @Override
    default void close() {
    }
}
