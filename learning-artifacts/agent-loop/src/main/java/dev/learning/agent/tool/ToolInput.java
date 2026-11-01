package dev.learning.agent.tool;

public sealed interface ToolInput permits CalculatorInput, SearchInput, BashInput {
    String toolName();
}
