package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.ModelGateway;
import dev.learning.agent.tool.ToolExecutionService;

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
    default void trace(RunScope scope, TraceEvent trace) {
        emit("trace", trace.scoped(requestId().toString(), mode(), scope));
    }
    default void emit(String type, Object data) { events().emit(new RuntimeEvent(type, data)); }
}
