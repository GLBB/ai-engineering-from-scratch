package dev.learning.agent.tool;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ToolSpec;

public interface AgentTool {
    ToolSpec definition();

    ToolInput decode(String arguments) throws java.io.IOException;

    ToolResult execute(ToolInput input, RunScope scope, RunContext context) throws Exception;

    /** Text compatibility for the offline transcript experiments. */
    default String execute(String arguments) throws Exception {
        ToolResult result = execute(decode(arguments), new RunScope("EXPERIMENT", null, 0), null);
        return result.status() == ToolStatus.SUCCESS ? result.content() : "error: " + result.content();
    }
}
