package dev.learning.agent.core;


public sealed interface ToolInput permits CalculatorInput, SearchInput, BashInput {
    String toolName();
}
