package dev.learning.agent.tool;

import java.util.List;
import java.util.function.UnaryOperator;

public record BashInput(String command) implements ToolInput {
    public String toolName() { return "bash"; }
    public List<String> templateStrings() { return List.of(command); }
    public ToolInput mapStrings(UnaryOperator<String> mapper) { return new BashInput(mapper.apply(command)); }
}
