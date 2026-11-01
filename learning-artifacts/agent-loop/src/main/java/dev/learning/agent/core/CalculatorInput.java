package dev.learning.agent.core;


public record CalculatorInput(String expression) implements ToolInput {
    public String toolName() { return "calculator"; }
}
