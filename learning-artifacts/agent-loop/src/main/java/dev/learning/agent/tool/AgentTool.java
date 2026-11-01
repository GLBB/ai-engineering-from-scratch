package dev.learning.agent.tool;

import dev.learning.agent.deepseek.model.ToolSpec;
import dev.learning.agent.core.*;

public interface AgentTool {
    ToolSpec definition();
    ToolResult execute(ToolInput input, RunScope scope, RunContext context) throws Exception;

    /** Text compatibility for the offline transcript experiments. */
    default String execute(String arguments) throws Exception {
        ToolResult result = execute(StrictJson.toolInput(definition().function().name(), arguments),
                new RunScope("EXPERIMENT", null, 0), null);
        return result.status() == ToolStatus.SUCCESS ? result.content() : "error: " + result.content();
    }
}
