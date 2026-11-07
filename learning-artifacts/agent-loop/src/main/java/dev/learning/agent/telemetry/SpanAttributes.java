package dev.learning.agent.telemetry;

import java.util.List;

/** SDK-independent attribute records, grouped by operation and lifecycle stage. */
public interface SpanAttributes {
    record Operation(@OtelAttribute("gen_ai.operation.name") String name,
        @OtelAttribute("gen_ai.agent.name") String agent) implements SpanAttributes {
    }

    record RunContext(@OtelAttribute("gen_ai.conversation.id") String conversationId,
        @OtelAttribute("agent_loop.semconv.revision") String semconvRevision,
        @OtelAttribute("agent_loop.request.id") String requestId, @OtelAttribute("agent_loop.mode") String mode,
        @OtelAttribute("agent_loop.protocol") String protocol) implements SpanAttributes {
    }

    record Phase(@OtelAttribute("agent_loop.phase") String phase, @OtelAttribute("agent_loop.step.id") String stepId,
        @OtelAttribute("agent_loop.plan.revision") long revision) implements SpanAttributes {
    }

    record RunAccepted(@OtelAttribute("agent_loop.run.started") boolean started,
        @OtelAttribute("agent_loop.run.delivery") String delivery) implements SpanAttributes {
    }

    record RunStarted(@OtelAttribute("agent_loop.run.started") boolean started) implements SpanAttributes {
    }

    record RunResult(@OtelAttribute("agent_loop.run.status") String status,
        @OtelAttribute("agent_loop.run.completion_check") String completionCheck,
        @OtelAttribute("agent_loop.run.error_code") String errorCode) implements SpanAttributes {
    }

    record RunCounters(@OtelAttribute("agent_loop.budget.model_calls") long modelCalls,
        @OtelAttribute("agent_loop.budget.tool_attempts") long toolAttempts,
        @OtelAttribute("agent_loop.budget.tools_executed") long toolsExecuted,
        @OtelAttribute("agent_loop.budget.step_attempts") long stepAttempts,
        @OtelAttribute("agent_loop.budget.plan_revisions") long planRevisions) implements SpanAttributes {
    }

    record ContentRestriction(
        @OtelAttribute("agent_loop.content.omitted_reason") String reason) implements SpanAttributes {
    }

    record ModelRequest(@OtelAttribute("gen_ai.provider.name") String provider,
        @OtelAttribute("gen_ai.request.model") String model, @OtelAttribute("gen_ai.request.max_tokens") long maxTokens,
        @OtelAttribute("gen_ai.request.reasoning.level") String reasoningEffort,
        @OtelAttribute("agent_loop.model.reasoning_level") String reasoningLevel,
        @OtelAttribute("gen_ai.output.type") String outputType, @OtelAttribute("agent_loop.model.round") int round,
        @OtelAttribute("agent_loop.model.use") String use,
        @OtelAttribute("agent_loop.model.message_count") int messageCount) implements SpanAttributes {
    }

    record ModelResponse(@OtelAttribute("gen_ai.response.finish_reasons") List<String> finishReasons,
        @OtelAttribute("agent_loop.model.tool_call_count") int toolCallCount,
        @OtelAttribute("gen_ai.response.id") String responseId, @OtelAttribute("gen_ai.response.model") String model,
        @OtelAttribute("agent_loop.model.reasoning_characters") int reasoningCharacters) implements SpanAttributes {
    }

    record ModelFirstChunk(
        @OtelAttribute("agent_loop.model.first_chunk_ms") long milliseconds) implements SpanAttributes {
    }

    record ModelFirstText(
        @OtelAttribute("agent_loop.model.first_text_ms") long milliseconds) implements SpanAttributes {
    }

    record ModelUsageComplete(
        @OtelAttribute("agent_loop.model.usage_complete") boolean complete) implements SpanAttributes {
    }

    record RawUsage(@OtelAttribute("agent_loop.model.usage_accounting") String accounting,
        @OtelAttribute("agent_loop.model.raw_usage") String json) implements SpanAttributes {
    }

    record TokenUsage(@OtelAttribute("gen_ai.usage.input_tokens") Long input,
        @OtelAttribute("gen_ai.usage.output_tokens") Long output,
        @OtelAttribute("gen_ai.usage.cache_read.input_tokens") Long cacheRead,
        @OtelAttribute("gen_ai.usage.cache_write.input_tokens") Long cacheWrite,
        @OtelAttribute("gen_ai.usage.reasoning.output_tokens") Long reasoning) implements SpanAttributes {
    }

    record ToolCall(@OtelAttribute("gen_ai.tool.name") String name, @OtelAttribute("gen_ai.tool.type") String type,
        @OtelAttribute("gen_ai.tool.call.id") String callId,
        @OtelAttribute("agent_loop.evidence.id") String evidenceId) implements SpanAttributes {
    }

    record ToolResult(@OtelAttribute("agent_loop.tool.status") String status,
        @OtelAttribute("agent_loop.tool.executed") boolean executed,
        @OtelAttribute("agent_loop.tool.has_value") boolean hasValue,
        @OtelAttribute("agent_loop.tool.truncated") boolean truncated,
        @OtelAttribute("agent_loop.tool.error_code") String errorCode,
        @OtelAttribute("agent_loop.tool.cached") boolean cached) implements SpanAttributes {
    }

    record ToolHttpRequests(@OtelAttribute("agent_loop.tool.http_requests") int count) implements SpanAttributes {
    }

    record EvidenceSaved(@OtelAttribute("agent_loop.evidence.id") String id,
        @OtelAttribute("agent_loop.tool.status") String status,
        @OtelAttribute("agent_loop.tool.executed") boolean executed) implements SpanAttributes {
    }

    record PlanAccepted(@OtelAttribute("agent_loop.plan.revision") int revision,
        @OtelAttribute("agent_loop.plan.step_count") int stepCount) implements SpanAttributes {
    }

    record PlanStepCount(@OtelAttribute("agent_loop.plan.step_count") int count) implements SpanAttributes {
    }

    record PlanDecision(@OtelAttribute("agent_loop.plan.decision") String decision) implements SpanAttributes {
    }

    record StepResult(@OtelAttribute("agent_loop.step.status") String status,
        @OtelAttribute("agent_loop.step.evidence_ids") List<String> evidenceIds) implements SpanAttributes {
    }

    record EventCode(@OtelAttribute("agent_loop.code") String code) implements SpanAttributes {
    }

    record Error(@OtelAttribute("error.type") String type) implements SpanAttributes {
    }

    record Cancellation(@OtelAttribute("agent_loop.cancelled") boolean cancelled,
        @OtelAttribute("agent_loop.cancel_reason") String reason) implements SpanAttributes {
    }

    record HttpRequest(@OtelAttribute("http.request.method") String method,
        @OtelAttribute("server.address") String serverAddress, @OtelAttribute("server.port") int serverPort,
        @OtelAttribute("url.scheme") String scheme, @OtelAttribute("url.path") String path) implements SpanAttributes {
    }

    record HttpResponse(@OtelAttribute("http.response.status_code") int statusCode) implements SpanAttributes {
    }

    record ProcessStarted(@OtelAttribute("process.executable.name") String executable) implements SpanAttributes {
    }

    record ProcessExited(@OtelAttribute("process.exit.code") int exitCode) implements SpanAttributes {
    }

    record ApprovalKind(@OtelAttribute("agent_loop.approval.kind") String kind) implements SpanAttributes {
    }

    record ApprovalOutcome(@OtelAttribute("agent_loop.approval.outcome") String outcome) implements SpanAttributes {
    }

    record WeatherFallback(@OtelAttribute("agent_loop.weather.fallback_used") boolean used) implements SpanAttributes {
    }

    record WeatherCandidates(@OtelAttribute("agent_loop.weather.candidate_count") int count) implements SpanAttributes {
    }

    record WeatherResolution(
        @OtelAttribute("agent_loop.weather.resolution") String resolution) implements SpanAttributes {
    }
}
