package dev.learning.agent.tool;

public record CalculatorInput(String expression) implements ToolInput {
    public String toolName() { return "calculator"; }
}
