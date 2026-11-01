package dev.learning.agent.core;

import java.util.UUID;

public record DefaultRunContext(UUID requestId, AgentMode mode, RunBudget budget,
                                RunControl control, RunEvents events, EvidenceStore evidence,
                                ModelGateway model, ToolExecutionService tools,
                                ApprovalGateway approvals) implements RunContext {}
