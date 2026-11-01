package dev.learning.agent.core;


public record SearchInput(String query) implements ToolInput {
    public String toolName() { return "search"; }
}
