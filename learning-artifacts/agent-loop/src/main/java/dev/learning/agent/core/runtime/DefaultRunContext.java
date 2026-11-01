package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.ModelGateway;
import dev.learning.agent.tool.ToolExecutionService;

import java.util.UUID;

public record DefaultRunContext(UUID requestId, AgentMode mode, RunBudget budget,
                                RunControl control, RunEvents events, EvidenceStore evidence,
                                ModelGateway model, ToolExecutionService tools,
                                ApprovalGateway approvals) implements RunContext {}
