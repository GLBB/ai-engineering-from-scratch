package dev.learning.agent.tool;

import java.util.List;
import java.util.function.UnaryOperator;

public record SearchInput(String query) implements ToolInput {
    public String toolName() { return "search"; }
    public List<String> templateStrings() { return List.of(query); }
    public ToolInput mapStrings(UnaryOperator<String> mapper) { return new SearchInput(mapper.apply(query)); }
}
