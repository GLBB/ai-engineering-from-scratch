package dev.learning.agent.tool;

import java.util.List;
import java.util.function.UnaryOperator;

/** Typed parameters expose only strings that permit ReWOO evidence substitution. */
public interface ToolInput {
    String toolName();
    List<String> templateStrings();
    ToolInput mapStrings(UnaryOperator<String> mapper);
}
