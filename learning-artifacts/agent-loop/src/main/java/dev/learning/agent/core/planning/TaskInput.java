package dev.learning.agent.core.planning;

import java.util.List;

public record TaskInput(String question, List<ConversationTurn> history) {
    public TaskInput { history = List.copyOf(history); }
    public record ConversationTurn(String question, String answer) {}
}
