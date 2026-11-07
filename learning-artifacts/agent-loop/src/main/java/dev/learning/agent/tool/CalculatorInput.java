package dev.learning.agent.tool;

import java.util.List;
import java.util.function.UnaryOperator;

public record CalculatorInput(String expression) implements ToolInput {
    public String toolName() {
        return "calculator";
    }

    public List<String> templateStrings() {
        return List.of(expression);
    }

    public ToolInput mapStrings(UnaryOperator<String> mapper) {
        return new CalculatorInput(mapper.apply(expression));
    }
}
