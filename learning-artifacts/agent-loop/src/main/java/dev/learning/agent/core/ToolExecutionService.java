package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.ToolCall;
import java.util.Set;

public interface ToolExecutionService {
    Evidence invoke(ToolInput input, String evidenceId, Set<String> allowedTools,
                    RunScope scope, RunContext context);
    Evidence invokeNative(ToolCall call, String evidenceId, Set<String> allowedTools,
                          RunScope scope, RunContext context);
}
