package dev.learning.agent.app.chat;

import dev.learning.agent.core.*;
import dev.learning.agent.tool.ToolRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Identity and lifecycle of one accepted request, including the pre-start window. */
final class RunLease {
    enum State { ACCEPTED, RUNNING, CLOSED }
    private final AtomicReference<State> state = new AtomicReference<>(State.ACCEPTED);
    private final AtomicReference<EventSink> events = new AtomicReference<>();
    private final ExecutionRequest request;
    private final AgentMode mode;
    private final AgentRunner runner;
    private final RunContext context;
    private final BrowserApproval approvals;
    private final Consumer<RunLease> release;

    RunLease(ExecutionRequest request, AgentMode mode, AgentRunner runner, ChatModel model,
             ToolRegistry tools, Consumer<RunLease> release) {
        this.request = request; this.mode = mode; this.runner = runner; this.release = release;
        approvals = new BrowserApproval(this::emit);
        context = RunContexts.create(request.requestId(), mode, model, tools, event -> {
            RuntimeEvent transport = (RuntimeEvent) event; emit(transport.type(), transport.data());
        }, approvals::request);
    }
    void arm() { context.control().onCancel(this::cancelBeforeStart); }
    UUID requestId() { return request.requestId(); }
    RunContext context() { return context; }
    State state() { return state.get(); }
    boolean tryStart() { return state.compareAndSet(State.ACCEPTED, State.RUNNING); }
    RunResult execute(EventSink sink) throws Exception {
        if (!tryStart()) return null;
        events.set(sink);
        try {
            context.control().check();
            emit("run_started", new Started(request.requestId(), mode, true));
            return runner.run(request, context);
        } finally { finish(); }
    }
    boolean decide(String approvalId, boolean approved) {
        return state.get() == State.RUNNING && approvals.decide(approvalId, approved);
    }
    void cancel(String reason) {
        if (state.get() == State.CLOSED) return;
        context.control().cancel(reason);
        cancelBeforeStart();
    }
    private void cancelBeforeStart() {
        if (state.compareAndSet(State.ACCEPTED, State.CLOSED)) cleanup();
    }
    void finish() {
        if (state.compareAndSet(State.RUNNING, State.CLOSED)) cleanup();
    }
    private void cleanup() {
        approvals.cancel();
        events.set(null);
        context.control().close();
        release.accept(this);
    }
    private void emit(String type, Object data) {
        EventSink sink = events.get();
        if (sink != null) sink.emit(type, data);
    }
    record Started(UUID requestId, AgentMode mode, boolean modeLocked) {}
}
