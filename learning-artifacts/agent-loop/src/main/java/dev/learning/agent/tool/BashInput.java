package dev.learning.agent.tool;

public record BashInput(String command) implements ToolInput {
    public String toolName() { return "bash"; }
}
