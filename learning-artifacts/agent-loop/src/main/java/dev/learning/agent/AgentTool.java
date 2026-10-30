package dev.learning.agent;

interface AgentTool {
    ToolSpec definition();
    String execute(String arguments) throws Exception;
}
