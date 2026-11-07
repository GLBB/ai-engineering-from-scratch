package dev.learning.agent.app.chat;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.AgentRunner;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.CompletionCheck;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.core.runtime.RuntimeEvent;
import dev.learning.agent.core.runtime.RunTelemetry;
import dev.learning.agent.core.runtime.TraceSpan;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.tool.ToolRegistry;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Identity and lifecycle of one accepted request, including the pre-start window. */
final class RunLease {
    enum State {
        ACCEPTED, RUNNING, CLOSED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.ACCEPTED);
    private final AtomicReference<EventSink> events = new AtomicReference<>();
    private final ExecutionRequest request;
    private final AgentMode mode;
    private final AgentRunner runner;
    private final RunContext context;
    private final BrowserApproval approvals;
    private final Consumer<RunLease> release;
    private final RunTelemetry telemetry;

    RunLease(ExecutionRequest request, AgentMode mode, AgentRunner runner, ChatModel model, ToolRegistry tools,
        Consumer<RunLease> release) {
        this(request, mode, runner, model, tools, release, RunTelemetry.NOOP);
    }

    RunLease(ExecutionRequest request, AgentMode mode, AgentRunner runner, ChatModel model, ToolRegistry tools,
        Consumer<RunLease> release, RunTelemetry telemetry) {
        this.request = request;
        this.mode = mode;
        this.runner = runner;
        this.release = release;
        this.telemetry = telemetry;
        approvals = new BrowserApproval(this::emit, telemetry.external());
        context = RunContexts.create(request.requestId(), mode, model, tools, event -> {
            RuntimeEvent transport = (RuntimeEvent) event;
            emit(transport.type(), transport.data());
        }, approvals::request, telemetry);
    }

    void arm() {
        context.control().onCancel(this::cancelBeforeStart);
    }

    UUID requestId() {
        return request.requestId();
    }

    RunContext context() {
        return context;
    }

    State state() {
        return state.get();
    }

    boolean tryStart() {
        return state.compareAndSet(State.ACCEPTED, State.RUNNING);
    }

    RunResult execute(EventSink sink) throws Exception {
        if (!tryStart()) {
            return null;
        }
        events.set(sink);
        try (TraceSpan activated = telemetry.activate();
            var requestLog = org.slf4j.MDC.putCloseable("requestId", request.requestId().toString()); var traceLog =
                org.slf4j.MDC.putCloseable("traceId", telemetry.traceId() == null ? "" : telemetry.traceId())) {
            context.control().check();
            emit("run_started", new Started(request.requestId(), mode, telemetry.traceId(), telemetry.traceUrl()));
            RunResult result = runner.run(request, context);
            telemetry.completed(result);
            return result;
        } catch (Exception error) {
            boolean cancelled = context.control().cancelled() || error instanceof InterruptedException
                || error instanceof java.io.UncheckedIOException;
            telemetry.completed(new RunResult(cancelled ? ExecutionStatus.CANCELLED : ExecutionStatus.FAILED,
                CompletionCheck.UNVERIFIED, "", "EXECUTION_ERROR", context.budget().snapshot()));
            telemetry.failed(error, cancelled);
            throw error;
        } finally {
            finish();
        }
    }

    boolean decide(String approvalId, boolean approved) {
        return state.get() == State.RUNNING && approvals.decide(approvalId, approved);
    }

    void cancel(String reason) {
        if (state.get() == State.CLOSED) {
            return;
        }
        telemetry.cancelled(state.get() == State.RUNNING);
        context.control().cancel(reason);
        cancelBeforeStart();
    }

    private void cancelBeforeStart() {
        if (state.compareAndSet(State.ACCEPTED, State.CLOSED)) {
            telemetry.cancelled(false);
            cleanup();
        }
    }

    void finish() {
        if (state.compareAndSet(State.RUNNING, State.CLOSED)) {
            cleanup();
        }
    }

    private void cleanup() {
        try {
            approvals.cancel();
            events.set(null);
            context.control().close();
        } finally {
            try {
                telemetry.end();
            } finally {
                release.accept(this);
            }
        }
    }

    private void emit(String type, Object data) {
        EventSink sink = events.get();
        if (sink != null) {
            sink.emit(type, data);
        }
    }

    record Started(UUID requestId, AgentMode mode, String traceId, String traceUrl) {
    }
}
