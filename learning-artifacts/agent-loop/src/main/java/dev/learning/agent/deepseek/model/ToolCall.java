package dev.learning.agent.deepseek.model;

public record ToolCall(String id, String type, FunctionCall function) {
    public record FunctionCall(String name, String arguments) {}
}
