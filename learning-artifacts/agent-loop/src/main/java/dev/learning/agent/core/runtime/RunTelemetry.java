package dev.learning.agent.core.runtime;

import dev.learning.agent.core.RunResult;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.tool.ToolResult;
import java.io.IOException;

/** Optional observation boundary; no SDK or backend types enter the execution algorithms. */
public interface RunTelemetry {
    RunTelemetry NOOP = new RunTelemetry() {};

    default TraceSpan activate() {
        return TraceSpan.NOOP;
    }

    default TraceSpan phase(RunScope scope) {
        return TraceSpan.NOOP;
    }

    default TraceSpan model(ModelRequest request, RunScope scope, int round) {
        return TraceSpan.NOOP;
    }

    default TraceSpan tool(String name, String arguments, String evidenceId, String nativeId, RunScope scope) {
        return TraceSpan.NOOP;
    }

    default void modelFinished(TraceSpan span, AssistantTurn answer) {
    }

    default void modelChunk(TraceSpan span, boolean text) {
    }

    default void modelUsage(TraceSpan span, dev.learning.agent.model.TokenUsage usage, boolean complete) {
    }

    default void toolFinished(TraceSpan span, ToolResult result) {
    }

    default void toolHttpRequests(int count) {
    }

    default void runtimeEvent(String type, Object data) {
    }

    default void event(String name, String code) {
    }

    default void completed(RunResult result) {
    }

    default void failed(Throwable error, boolean cancelled) {
    }

    default void cancelled(boolean started) {
    }

    default void end() {
    }

    default void restrictContent() {
    }

    default boolean contentRestricted() {
        return false;
    }

    default String traceId() {
        return null;
    }

    default String traceUrl() {
        return null;
    }

    default ExternalObserver external() {
        return ExternalObserver.NOOP;
    }

    default <T> T observe(RunScope scope, Operation<T> operation) throws IOException, InterruptedException {
        try (TraceSpan span = phase(scope)) {
            try {
                return operation.run();
            } catch (IOException | InterruptedException | RuntimeException error) {
                span.fail(error);
                throw error;
            }
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException, InterruptedException;
    }
}
