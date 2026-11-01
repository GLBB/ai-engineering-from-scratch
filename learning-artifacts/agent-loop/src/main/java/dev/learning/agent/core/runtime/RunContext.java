package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.model.ModelGateway;
import dev.learning.agent.tool.ToolExecutionService;

import dev.learning.agent.tool.ToolStatus;
import java.util.stream.Collectors;
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
    default boolean needsInput() {
        return evidence().snapshot().stream().anyMatch(item -> item.result().status() == ToolStatus.NEEDS_INPUT);
    }
    default String inputSummary() {
        return "还需要补充信息才能继续：\n" + evidence().snapshot().stream()
                .filter(item -> item.result().status() == ToolStatus.NEEDS_INPUT)
                .map(item -> item.result().content()).collect(Collectors.joining("\n"));
    }
    default void trace(RunScope scope, TraceEvent trace) {
        emit("trace", trace.scoped(requestId().toString(), mode(), scope));
    }
    default void emit(String type, Object data) { events().emit(new RuntimeEvent(type, data)); }
}
