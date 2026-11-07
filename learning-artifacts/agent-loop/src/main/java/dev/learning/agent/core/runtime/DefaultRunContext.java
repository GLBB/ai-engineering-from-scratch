package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.ModelGateway;
import dev.learning.agent.tool.ToolExecutionService;

import java.util.UUID;

public record DefaultRunContext(UUID requestId, AgentMode mode, RunBudget budget, RunControl control, RunEvents events,
    EvidenceStore evidence, ModelGateway model, ToolExecutionService tools, ApprovalGateway approvals,
    RunTelemetry telemetry) implements RunContext {
    public DefaultRunContext(UUID requestId, AgentMode mode, RunBudget budget, RunControl control, RunEvents events,
        EvidenceStore evidence, ModelGateway model, ToolExecutionService tools, ApprovalGateway approvals) {
        this(requestId, mode, budget, control, events, evidence, model, tools, approvals, RunTelemetry.NOOP);
    }
}
