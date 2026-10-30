package dev.learning.agent.tool;

import dev.learning.agent.deepseek.model.ToolSpec;

public interface AgentTool {
    ToolSpec definition();
    String execute(String arguments) throws Exception;
}
