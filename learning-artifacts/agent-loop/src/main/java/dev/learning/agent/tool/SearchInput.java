package dev.learning.agent.tool;

public record SearchInput(String query) implements ToolInput {
    public String toolName() { return "search"; }
}
