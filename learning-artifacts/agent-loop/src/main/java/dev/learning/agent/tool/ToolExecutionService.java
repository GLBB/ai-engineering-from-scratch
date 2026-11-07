package dev.learning.agent.tool;

import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ToolCall;

import java.util.Set;

public interface ToolExecutionService {
    Set<String> availableTools();

    Evidence invoke(ToolInput input, String evidenceId, Set<String> allowedTools, RunScope scope, RunContext context);

    Evidence invokeNative(ToolCall call, String evidenceId, Set<String> allowedTools, RunScope scope,
        RunContext context);
}
