package dev.learning.agent.observability;

import dev.learning.agent.core.*;
import dev.learning.agent.core.planning.ModelJson;
import dev.learning.agent.core.planexecute.GoalPlan;
import dev.learning.agent.core.planexecute.ReplanDecision;
import dev.learning.agent.core.planexecute.StepResult;
import dev.learning.agent.core.rewoo.RewooPlan;
import dev.learning.agent.core.runtime.*;
import dev.learning.agent.model.*;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.ToolResult;
import dev.learning.agent.tool.ToolStatus;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Semantic mapping pinned to OtelTelemetryFactory.SEMCONV; one instance belongs to one lease. */
final class OtelRunTelemetry implements RunTelemetry {
    private static final List<String> TOOLS = List.of("calculator", "bash", "search", "current_weather", "weather_forecast");
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
        this.factory = factory; this.tracer = tracer; this.content = content; this.session = session;
        this.request = request; this.mode = mode.name(); this.protocol = protocol; this.release = release;
        agent = "agent-loop." + mode.name().toLowerCase(Locale.ROOT);
        root = tracer.spanBuilder("invoke_agent " + agent).setNoParent().setSpanKind(SpanKind.INTERNAL)
                .setAttribute("gen_ai.operation.name", "invoke_agent").setAttribute("gen_ai.agent.name", agent).startSpan();
        common(root, "agent");
        root.setAttribute("agent_loop.run.started", false);
        root.setAttribute("agent_loop.run.delivery", "not_confirmed");
        context = Context.root().with(OtelTelemetryFactory.RUN, this).with(root);
        updateRootInput();
    }
    private void common(Span span, String type) {
        span.setAttribute("langfuse.observation.type", type);
        span.setAttribute("gen_ai.conversation.id", session);
        span.setAttribute("langfuse.session.id", session);
        span.setAttribute("langfuse.trace.name", "invoke_agent " + agent);
        span.setAttribute("langfuse.environment", "local");
        span.setAttribute("langfuse.release", release);
        span.setAttribute("agent_loop.semconv.revision", OtelTelemetryFactory.SEMCONV);
        span.setAttribute("agent_loop.request.id", request.requestId().toString());
        span.setAttribute("agent_loop.mode", mode);
        span.setAttribute("agent_loop.protocol", protocol);
        span.setAttribute("agent_loop.content.mode", content.mode());
        for (String prefix : List.of("langfuse.trace.metadata.", "langfuse.observation.metadata.")) {
            span.setAttribute(prefix + "mode", mode);
            span.setAttribute(prefix + "protocol", protocol);
            span.setAttribute(prefix + "requestId", request.requestId().toString());
        }
    }
    private void scope(Span span, RunScope scope) {
        if (scope == null) return;
        span.setAttribute("agent_loop.phase", scope.phase());
        span.setAttribute("langfuse.observation.metadata.phase", scope.phase());
        if (scope.stepId() != null) {
            span.setAttribute("agent_loop.step.id", scope.stepId());
            span.setAttribute("langfuse.observation.metadata.stepId", scope.stepId());
        }
        span.setAttribute("agent_loop.plan.revision", scope.revision());
        span.setAttribute("langfuse.observation.metadata.revision", scope.revision());
    }
    OtelSpan operation(String name, String type, SpanKind kind, RunScope scope) {
        Span span = tracer.spanBuilder(name).setParent(Context.current()).setSpanKind(kind).startSpan();
        common(span, type); scope(span, scope);
        return new OtelSpan(span, span.makeCurrent(), true);
    }
    public TraceSpan activate() {
        root.setAttribute("agent_loop.run.started", true);
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
            span.attribute("gen_ai.operation.name", "plan");
            span.attribute("gen_ai.agent.name", agent);
        }
        return span;
    }
    public TraceSpan model(ModelRequest model, RunScope scope, int round) {
        if (model.messages().stream().anyMatch(m -> m.toolCalls() != null
                && m.toolCalls().stream().anyMatch(c -> c.function() != null && "bash".equals(c.function().name())))) restrictContent();
        var span = operation("chat " + model.settings().model(), "generation", SpanKind.CLIENT, scope);
        span.attribute("gen_ai.operation.name", "chat");
        span.attribute("gen_ai.provider.name", "deepseek");
        span.attribute("gen_ai.request.model", model.settings().model());
        span.attribute("gen_ai.request.max_tokens", model.maxOutputTokens());
        span.attribute("gen_ai.request.reasoning.level", model.settings().apiReasoningEffort());
        span.attribute("agent_loop.model.reasoning_level", model.settings().reasoningLevel());
        span.attribute("gen_ai.output.type", model.outputMode() == OutputMode.JSON_OBJECT ? "json" : "text");
        span.attribute("agent_loop.model.round", round);
        span.attribute("agent_loop.model.use", model.use().name());
        span.attribute("agent_loop.model.message_count", model.messages().size());
        span.attribute("langfuse.observation.input", StrictJson.write(new ModelInput(model.messages().size(), model.tools().size(), model.use().name())));
        return span;
    }
    public void modelChunk(TraceSpan operation, boolean text) {
        if (!(operation instanceof OtelSpan span)) return;
        long elapsed = (System.nanoTime() - span.started) / 1_000_000;
        if (span.firstChunk == null) {
            span.firstChunk = elapsed;
            span.attribute("agent_loop.model.first_chunk_ms", elapsed);
            span.attribute("langfuse.observation.completion_start_time", Instant.now().toString());
        }
        if (text && span.firstText == null) {
            span.firstText = elapsed;
            span.attribute("agent_loop.model.first_text_ms", elapsed);
        }
    }
    public void modelFinished(TraceSpan operation, AssistantTurn answer) {
        if (!(operation instanceof OtelSpan span)) return;
        if (answer.calls().stream().anyMatch(c -> "bash".equals(c.function().name()))
                || answer.content().toLowerCase(Locale.ROOT).contains("bash")) restrictContent();
        span.span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"), List.of(answer.finishReason()));
        span.attribute("agent_loop.model.tool_call_count", answer.calls().size());
        if (answer.metadata() != null) {
            span.attribute("gen_ai.response.id", answer.metadata().id());
            span.attribute("gen_ai.response.model", answer.metadata().model());
        }
        span.attribute("agent_loop.model.reasoning_characters", answer.reasoningContent() == null ? 0 : answer.reasoningContent().length());
        span.attribute("langfuse.observation.output", StrictJson.write(new ModelOutput(answer.finishReason(),
                answer.calls().stream().limit(16).map(c -> toolName(c.function().name())).toList(), answer.content().length(),
                content.preview(answer.content(), restricted.get()))));
        if (!span.sawUsage) modelUsage(span, answer.usage(), true);
    }
    public void modelUsage(TraceSpan operation, TokenUsage usage, boolean complete) {
        if (!(operation instanceof OtelSpan span) || usage == null) return;
        span.sawUsage = true;
        span.attribute("agent_loop.model.usage_complete", complete);
        UsageMapping.apply(span, usage);
    }
    public TraceSpan tool(String name, String arguments, String evidenceId, String nativeId, RunScope scope) {
        if ("bash".equals(name)) restrictContent();
        String known = toolName(name);
        var span = operation("execute_tool " + known, "tool", SpanKind.INTERNAL, scope);
        span.tool = known;
        span.attribute("gen_ai.operation.name", "execute_tool");
        span.attribute("gen_ai.tool.name", known);
        span.attribute("gen_ai.tool.type", "function");
        span.attribute("gen_ai.tool.call.id", nativeId);
        span.attribute("agent_loop.evidence.id", evidenceId);
        span.attribute("langfuse.observation.input", StrictJson.write(content.toolInput(known, arguments, restricted.get())));
        return span;
    }
    public void toolFinished(TraceSpan operation, ToolResult result) {
        if (!(operation instanceof OtelSpan span)) return;
        span.attribute("agent_loop.tool.status", result.status().name());
        span.attribute("agent_loop.tool.executed", result.executed());
        span.attribute("agent_loop.tool.has_value", result.hasValue());
        span.attribute("agent_loop.tool.truncated", result.truncated());
        span.attribute("agent_loop.tool.error_code", result.errorCode());
        span.attribute("agent_loop.tool.cached", result.status() == ToolStatus.SUCCESS && !result.executed());
        boolean allow = List.of("calculator", "current_weather", "weather_forecast").contains(span.tool);
        span.attribute("langfuse.observation.output", StrictJson.write(new ToolOutput(result.status().name(),
                result.executed(), result.hasValue(), result.truncated(), result.sources().size(),
                content.preview(result.content(), restricted.get() || !allow))));
        if (result.status() == ToolStatus.ERROR || result.status() == ToolStatus.TIMEOUT)
            span.error(result.errorCode() == null ? "TOOL_FAILED" : result.errorCode());
        else if (result.status() == ToolStatus.DENIED || result.status() == ToolStatus.SKIPPED)
            span.attribute("langfuse.observation.level", "WARNING");
    }
    public void runtimeEvent(String type, Object data) {
        Span span = Span.current();
        if (data instanceof Evidence evidence) {
            span.addEvent("agent_loop.evidence.saved", Attributes.builder().put("agent_loop.evidence.id", evidence.id())
                    .put("agent_loop.tool.status", evidence.result().status().name()).put("agent_loop.tool.executed", evidence.result().executed()).build());
            if (evidence.result().status() == ToolStatus.SKIPPED) span.addEvent("agent_loop.step.skipped");
        } else if (data instanceof ModelJson.PlanDisplay plan) {
            int count = plan.plan() instanceof RewooPlan value ? value.steps().size()
                    : plan.plan() instanceof GoalPlan value ? value.steps().size() : 0;
            span.setAttribute("agent_loop.plan.step_count", count);
            span.addEvent("agent_loop.plan.accepted", Attributes.builder().put("agent_loop.plan.revision", plan.revision())
                    .put("agent_loop.plan.step_count", count).build());
        } else if (data instanceof ReplanDecision decision) {
            String value = decision instanceof ReplanDecision.ReviseDecision ? "REVISE" : decision instanceof ReplanDecision.FinishDecision ? "FINISH"
                    : decision instanceof ReplanDecision.StopDecision ? "STOP" : "CONTINUE";
            span.setAttribute("agent_loop.plan.decision", value);
            span.addEvent("agent_loop.plan.decision", Attributes.of(AttributeKey.stringKey("agent_loop.plan.decision"), value));
        } else if (data instanceof StepResult result) {
            span.setAttribute("agent_loop.step.status", result.status().name());
            span.setAttribute(AttributeKey.stringArrayKey("agent_loop.step.evidence_ids"), result.evidenceIds());
            span.setAttribute("langfuse.observation.output", StrictJson.write(new StepOutput(result.status().name(),
                    result.evidenceIds(), content.preview(result.summary(), restricted.get()))));
            span.addEvent("agent_loop.step.result");
        }
    }
    public void event(String name, String code) {
        Span.current().addEvent(name, Attributes.of(AttributeKey.stringKey("agent_loop.code"), code));
    }
    public void toolHttpRequests(int count) { Span.current().setAttribute("agent_loop.tool.http_requests", count); }
    public void completed(RunResult result) {
        root.setAttribute("agent_loop.run.status", result.status().name());
        root.setAttribute("agent_loop.run.completion_check", result.completionCheck().name());
        if (result.errorCode() != null) root.setAttribute("agent_loop.run.error_code", result.errorCode());
        var count = result.counters();
        root.setAttribute("agent_loop.budget.model_calls", count.modelCalls());
        root.setAttribute("agent_loop.budget.tool_attempts", count.toolAttempts());
        root.setAttribute("agent_loop.budget.tools_executed", count.toolsExecuted());
        root.setAttribute("agent_loop.budget.step_attempts", count.stepAttempts());
        root.setAttribute("agent_loop.budget.plan_revisions", count.planRevisions());
        root.setAttribute("langfuse.observation.output", StrictJson.write(new RunOutput(result.status().name(),
                result.completionCheck().name(), result.answer().length(), content.preview(result.answer(), restricted.get()))));
        if (result.status() == ExecutionStatus.FAILED) new OtelSpan(root, () -> {}, false).error(result.errorCode() == null ? "RUN_FAILED" : result.errorCode());
        else if (result.status() == ExecutionStatus.PARTIAL) root.setAttribute("langfuse.observation.level", "WARNING");
    }
    public void failed(Throwable error, boolean cancelled) {
        root.setAttribute("agent_loop.run.status", cancelled || error instanceof java.io.UncheckedIOException ? "CANCELLED" : "FAILED");
        if (!cancelled) new OtelSpan(root, () -> {}, false).fail(error);
    }
    public void cancelled(boolean started) {
        root.setAttribute("agent_loop.run.started", started);
        if (cancelNoted.compareAndSet(false, true)) root.addEvent("agent_loop.cancel.requested");
        if (!started) {
            root.setAttribute("agent_loop.run.status", "CANCELLED");
            root.setAttribute("langfuse.observation.output", StrictJson.write(new RunOutput("CANCELLED", "UNVERIFIED", 0, null)));
            for (String counter : List.of("model_calls", "tool_attempts", "tools_executed", "step_attempts", "plan_revisions"))
                root.setAttribute("agent_loop.budget." + counter, 0L);
        }
    }
    public void restrictContent() {
        restricted.set(true);
        root.setAttribute("agent_loop.content.omitted_reason", "BASH_CONTEXT");
        updateRootInput();
    }
    public boolean contentRestricted() { return restricted.get(); }
    private void updateRootInput() {
        root.setAttribute("langfuse.observation.input", StrictJson.write(new RunInput(mode, request.settings().model(),
                request.prompt().length(), content.preview(request.prompt(), restricted.get()))));
    }
    public String traceId() { return root.getSpanContext().isSampled() ? root.getSpanContext().getTraceId() : null; }
    public String traceUrl() { return traceId() == null ? null : factory.url(traceId()); }
    public ExternalObserver external() { return factory.external(); }
    public void end() { if (ended.compareAndSet(false, true)) root.end(); }
    private static String toolName(String value) { return TOOLS.contains(value) ? value : "unknown_tool"; }
    record RunInput(String mode, String model, int questionCharacters, String questionPreview) {}
    record RunOutput(String status, String completionCheck, int answerCharacters, String answerPreview) {}
    record ModelInput(int messageCount, int availableTools, String use) {}
    record ModelOutput(String finishReason, List<String> tools, int textCharacters, String textPreview) {}
    record ToolOutput(String status, boolean executed, boolean hasValue, boolean truncated, int sources, String preview) {}
    record StepOutput(String status, List<String> evidenceIds, String summaryPreview) {}
}
