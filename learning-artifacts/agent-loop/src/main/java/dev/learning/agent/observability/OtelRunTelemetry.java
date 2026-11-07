package dev.learning.agent.observability;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.planning.ModelJson;
import dev.learning.agent.core.planexecute.GoalPlan;
import dev.learning.agent.core.planexecute.ReplanDecision;
import dev.learning.agent.core.planexecute.StepResult;
import dev.learning.agent.core.rewoo.RewooPlan;
import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.ExternalObserver;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.core.runtime.RunTelemetry;
import dev.learning.agent.core.runtime.TraceSpan;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.OutputMode;
import dev.learning.agent.model.TokenUsage;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.telemetry.LangfuseAttributes;
import dev.learning.agent.telemetry.SpanAttributes;
import dev.learning.agent.tool.ToolResult;
import dev.learning.agent.tool.ToolStatus;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Semantic mapping pinned to OtelTelemetryFactory.SEMCONV; one instance belongs to one lease. */
final class OtelRunTelemetry implements RunTelemetry {
    private static final List<String> TOOLS =
        List.of("calculator", "bash", "search", "current_weather", "weather_forecast");
    private final OtelTelemetryFactory factory;
    private final Tracer tracer;
    private final TraceContentPolicy content;
    private final ExecutionRequest request;
    private final String session;
    private final String mode;
    private final String protocol;
    private final String agent;
    private final String release;
    private final Span root;
    private final Context context;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean restricted = new AtomicBoolean();
    private final AtomicBoolean cancelNoted = new AtomicBoolean();

    OtelRunTelemetry(OtelTelemetryFactory factory, Tracer tracer, TraceContentPolicy content, String session,
        ExecutionRequest request, AgentMode mode, String protocol, String release) {
        this.factory = factory;
        this.tracer = tracer;
        this.content = content;
        this.session = session;
        this.request = request;
        this.mode = mode.name();
        this.protocol = protocol;
        this.release = release;
        agent = "agent-loop." + mode.name().toLowerCase(Locale.ROOT);
        root = tracer.spanBuilder("invoke_agent " + agent).setNoParent().setSpanKind(SpanKind.INTERNAL)
            .setAllAttributes(OtelAttributeWriter.attributes(new SpanAttributes.Operation("invoke_agent", agent)))
            .startSpan();
        common(root, "agent");
        OtelAttributeWriter.write(root, new SpanAttributes.RunAccepted(false, "not_confirmed"));
        context = Context.root().with(OtelTelemetryFactory.RUN, this).with(root);
        updateRootInput();
    }

    private void common(Span span, String type) {
        OtelAttributeWriter.write(span, new SpanAttributes.RunContext(session, OtelTelemetryFactory.SEMCONV,
            request.requestId().toString(), mode, protocol));
        OtelAttributeWriter.write(span, new LangfuseAttributes.Context(type, session, "invoke_agent " + agent, "local",
            release, mode, protocol, request.requestId().toString()));
    }

    private void scope(Span span, RunScope scope) {
        if (scope == null) {
            return;
        }
        OtelAttributeWriter.write(span, new SpanAttributes.Phase(scope.phase(), scope.stepId(), scope.revision()));
        OtelAttributeWriter.write(span, new LangfuseAttributes.Phase(scope.phase(), scope.stepId(), scope.revision()));
    }

    OtelSpan operation(String name, String type, SpanKind kind, RunScope scope) {
        Span span = tracer.spanBuilder(name).setParent(Context.current()).setSpanKind(kind).startSpan();
        common(span, type);
        scope(span, scope);
        return new OtelSpan(span, span.makeCurrent(), true);
    }

    public TraceSpan activate() {
        OtelAttributeWriter.write(root, new SpanAttributes.RunStarted(true));
        root.addEvent("agent_loop.run.started");
        return new OtelSpan(root, context.makeCurrent(), false);
    }

    public TraceSpan phase(RunScope scope) {
        String name = switch (scope.phase()) {
            case "PLAN" -> "plan " + agent;
            case "WORK" -> "agent_loop.work";
            case "EXECUTE" -> "agent_loop.step";
            case "REPLAN" -> "agent_loop.review";
            case "REPAIR" -> "agent_loop.json.repair";
            case "SOLVE" -> "agent_loop.solve";
            case "FINALIZE" -> "agent_loop.finalize";
            case "CLARIFY" -> "agent_loop.clarify";
            default -> "agent_loop.phase";
        };
        var span = operation(name, "chain", SpanKind.INTERNAL, scope);
        if (scope.phase().equals("PLAN")) {
            span.attributes(new SpanAttributes.Operation("plan", agent));
        }
        return span;
    }

    public TraceSpan model(ModelRequest model, RunScope scope, int round) {
        if (model.messages().stream().anyMatch(m -> m.toolCalls() != null
            && m.toolCalls().stream().anyMatch(c -> c.function() != null && "bash".equals(c.function().name())))) {
            restrictContent();
        }
        var span = operation("chat " + model.settings().model(), "generation", SpanKind.CLIENT, scope);
        span.attributes(new SpanAttributes.Operation("chat", null));
        span.attributes(new SpanAttributes.ModelRequest("deepseek", model.settings().model(), model.maxOutputTokens(),
            model.settings().apiReasoningEffort(), model.settings().reasoningLevel(),
            model.outputMode() == OutputMode.JSON_OBJECT ? "json" : "text", round, model.use().name(),
            model.messages().size()));
        span.attributes(new LangfuseAttributes.Input(
            StrictJson.write(new ModelInput(model.messages().size(), model.tools().size(), model.use().name()))));
        return span;
    }

    public void modelChunk(TraceSpan operation, boolean text) {
        if (!(operation instanceof OtelSpan span)) {
            return;
        }
        long elapsed = (System.nanoTime() - span.started) / 1_000_000;
        if (span.firstChunk == null) {
            span.firstChunk = elapsed;
            span.attributes(new SpanAttributes.ModelFirstChunk(elapsed));
            span.attributes(new LangfuseAttributes.CompletionStart(Instant.now().toString()));
        }
        if (text && span.firstText == null) {
            span.firstText = elapsed;
            span.attributes(new SpanAttributes.ModelFirstText(elapsed));
        }
    }

    public void modelFinished(TraceSpan operation, AssistantTurn answer) {
        if (!(operation instanceof OtelSpan span)) {
            return;
        }
        if (answer.calls().stream().anyMatch(c -> "bash".equals(c.function().name()))
            || answer.content().toLowerCase(Locale.ROOT).contains("bash")) {
            restrictContent();
        }
        span.attributes(new SpanAttributes.ModelResponse(List.of(answer.finishReason()), answer.calls().size(),
            answer.metadata() == null ? null : answer.metadata().id(),
            answer.metadata() == null ? null : answer.metadata().model(),
            answer.reasoningContent() == null ? 0 : answer.reasoningContent().length()));
        span.attributes(new LangfuseAttributes.Output(StrictJson.write(new ModelOutput(answer.finishReason(),
            answer.calls().stream().limit(16).map(c -> toolName(c.function().name())).toList(),
            answer.content().length(), content.preview(answer.content(), restricted.get())))));
        if (!span.sawUsage) {
            modelUsage(span, answer.usage(), true);
        }
    }

    public void modelUsage(TraceSpan operation, TokenUsage usage, boolean complete) {
        if (!(operation instanceof OtelSpan span) || usage == null) {
            return;
        }
        span.sawUsage = true;
        span.attributes(new SpanAttributes.ModelUsageComplete(complete));
        UsageMapping.apply(span, usage);
    }

    public TraceSpan tool(String name, String arguments, String evidenceId, String nativeId, RunScope scope) {
        if ("bash".equals(name)) {
            restrictContent();
        }
        String known = toolName(name);
        var span = operation("execute_tool " + known, "tool", SpanKind.INTERNAL, scope);
        span.tool = known;
        span.attributes(new SpanAttributes.Operation("execute_tool", null));
        span.attributes(new SpanAttributes.ToolCall(known, "function", nativeId, evidenceId));
        span.attributes(
            new LangfuseAttributes.Input(StrictJson.write(content.toolInput(known, arguments, restricted.get()))));
        return span;
    }

    public void toolFinished(TraceSpan operation, ToolResult result) {
        if (!(operation instanceof OtelSpan span)) {
            return;
        }
        span.attributes(new SpanAttributes.ToolResult(result.status().name(), result.executed(), result.hasValue(),
            result.truncated(), result.errorCode(), result.status() == ToolStatus.SUCCESS && !result.executed()));
        boolean allow = List.of("calculator", "current_weather", "weather_forecast").contains(span.tool);
        span.attributes(new LangfuseAttributes.Output(StrictJson
            .write(new ToolOutput(result.status().name(), result.executed(), result.hasValue(), result.truncated(),
                result.sources().size(), content.preview(result.content(), restricted.get() || !allow)))));
        if (result.status() == ToolStatus.ERROR || result.status() == ToolStatus.TIMEOUT) {
            span.error(result.errorCode() == null ? "TOOL_FAILED" : result.errorCode());
        } else if (result.status() == ToolStatus.DENIED || result.status() == ToolStatus.SKIPPED) {
            span.attributes(new LangfuseAttributes.Level("WARNING"));
        }
    }

    public void runtimeEvent(String type, Object data) {
        Span span = Span.current();
        if (data instanceof Evidence evidence) {
            span.addEvent("agent_loop.evidence.saved",
                OtelAttributeWriter.attributes(new SpanAttributes.EvidenceSaved(evidence.id(),
                    evidence.result().status().name(), evidence.result().executed())));
            if (evidence.result().status() == ToolStatus.SKIPPED) {
                span.addEvent("agent_loop.step.skipped");
            }
        } else if (data instanceof ModelJson.PlanDisplay plan) {
            int count = plan.plan() instanceof RewooPlan value ? value.steps().size()
                : plan.plan() instanceof GoalPlan value ? value.steps().size() : 0;
            OtelAttributeWriter.write(span, new SpanAttributes.PlanStepCount(count));
            span.addEvent("agent_loop.plan.accepted",
                OtelAttributeWriter.attributes(new SpanAttributes.PlanAccepted(plan.revision(), count)));
        } else if (data instanceof ReplanDecision decision) {
            String value = decision instanceof ReplanDecision.ReviseDecision ? "REVISE"
                : decision instanceof ReplanDecision.FinishDecision ? "FINISH"
                    : decision instanceof ReplanDecision.StopDecision ? "STOP" : "CONTINUE";
            var attributes = new SpanAttributes.PlanDecision(value);
            OtelAttributeWriter.write(span, attributes);
            span.addEvent("agent_loop.plan.decision", OtelAttributeWriter.attributes(attributes));
        } else if (data instanceof StepResult result) {
            OtelAttributeWriter.write(span,
                new SpanAttributes.StepResult(result.status().name(), result.evidenceIds()));
            OtelAttributeWriter.write(span,
                new LangfuseAttributes.Output(StrictJson.write(new StepOutput(result.status().name(),
                    result.evidenceIds(), content.preview(result.summary(), restricted.get())))));
            span.addEvent("agent_loop.step.result");
        }
    }

    public void event(String name, String code) {
        Span.current().addEvent(name, OtelAttributeWriter.attributes(new SpanAttributes.EventCode(code)));
    }

    public void toolHttpRequests(int count) {
        OtelAttributeWriter.write(Span.current(), new SpanAttributes.ToolHttpRequests(count));
    }

    public void completed(RunResult result) {
        OtelAttributeWriter.write(root,
            new SpanAttributes.RunResult(result.status().name(), result.completionCheck().name(), result.errorCode()));
        var count = result.counters();
        OtelAttributeWriter.write(root, new SpanAttributes.RunCounters(count.modelCalls(), count.toolAttempts(),
            count.toolsExecuted(), count.stepAttempts(), count.planRevisions()));
        OtelAttributeWriter.write(root,
            new LangfuseAttributes.Output(
                StrictJson.write(new RunOutput(result.status().name(), result.completionCheck().name(),
                    result.answer().length(), content.preview(result.answer(), restricted.get())))));
        if (result.status() == ExecutionStatus.FAILED) {
            new OtelSpan(root, () -> {
            }, false).error(result.errorCode() == null ? "RUN_FAILED" : result.errorCode());
        } else if (result.status() == ExecutionStatus.PARTIAL) {
            OtelAttributeWriter.write(root, new LangfuseAttributes.Level("WARNING"));
        }
    }

    public void failed(Throwable error, boolean cancelled) {
        OtelAttributeWriter.write(root, new SpanAttributes.RunResult(
            cancelled || error instanceof java.io.UncheckedIOException ? "CANCELLED" : "FAILED", null, null));
        if (!cancelled) {
            new OtelSpan(root, () -> {
            }, false).fail(error);
        }
    }

    public void cancelled(boolean started) {
        OtelAttributeWriter.write(root, new SpanAttributes.RunStarted(started));
        if (cancelNoted.compareAndSet(false, true)) {
            root.addEvent("agent_loop.cancel.requested");
        }
        if (!started) {
            OtelAttributeWriter.write(root, new SpanAttributes.RunResult("CANCELLED", null, null));
            OtelAttributeWriter.write(root,
                new LangfuseAttributes.Output(StrictJson.write(new RunOutput("CANCELLED", "UNVERIFIED", 0, null))));
            OtelAttributeWriter.write(root, new SpanAttributes.RunCounters(0, 0, 0, 0, 0));
        }
    }

    public void restrictContent() {
        restricted.set(true);
        OtelAttributeWriter.write(root, new SpanAttributes.ContentRestriction("BASH_CONTEXT"));
        updateRootInput();
    }

    public boolean contentRestricted() {
        return restricted.get();
    }

    private void updateRootInput() {
        OtelAttributeWriter.write(root,
            new LangfuseAttributes.Input(StrictJson.write(new RunInput(mode, request.settings().model(),
                request.prompt().length(), content.preview(request.prompt(), restricted.get())))));
    }

    public String traceId() {
        return root.getSpanContext().isSampled() ? root.getSpanContext().getTraceId() : null;
    }

    public String traceUrl() {
        return traceId() == null ? null : factory.url(traceId());
    }

    public ExternalObserver external() {
        return factory.external();
    }

    public void end() {
        if (ended.compareAndSet(false, true)) {
            root.end();
        }
    }

    private static String toolName(String value) {
        return TOOLS.contains(value) ? value : "unknown_tool";
    }

    record RunInput(String mode, String model, int questionCharacters, String questionPreview) {
    }

    record RunOutput(String status, String completionCheck, int answerCharacters, String answerPreview) {
    }

    record ModelInput(int messageCount, int availableTools, String use) {
    }

    record ModelOutput(String finishReason, List<String> tools, int textCharacters, String textPreview) {
    }

    record ToolOutput(String status, boolean executed, boolean hasValue, boolean truncated, int sources,
        String preview) {
    }

    record StepOutput(String status, List<String> evidenceIds, String summaryPreview) {
    }
}
