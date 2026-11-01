package dev.learning.agent.core;


public record BashInput(String command) implements ToolInput {
    public String toolName() { return "bash"; }
}
