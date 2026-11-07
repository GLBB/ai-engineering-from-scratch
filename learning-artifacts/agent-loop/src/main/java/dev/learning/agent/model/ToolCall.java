package dev.learning.agent.model;

public record ToolCall(String id, String type, FunctionCall function) {
    public record FunctionCall(String name, String arguments) {
    }
}
