package dev.learning.agent.core;

import java.util.UUID;

public interface RunContext {
    UUID requestId();
    AgentMode mode();
    RunBudget budget();
    RunControl control();
    RunEvents events();
    EvidenceStore evidence();
    ModelGateway model();
    ToolExecutionService tools();
    ApprovalGateway approvals();
    default void emit(String type, Object data) { events().emit(new RuntimeEvent(type, data)); }
}
