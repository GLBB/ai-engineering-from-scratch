package dev.learning.agent.core.planning;

import java.util.ArrayList;
import java.util.List;

/** Only completed, shareable answers cross user turns in planning modes. */
public final class ConversationMemory {
    private final List<TaskInput.ConversationTurn> turns = new ArrayList<>();
    public TaskInput snapshot(String question) { return new TaskInput(question, turns); }
    public void commit(String question, String answer) {
        turns.add(new TaskInput.ConversationTurn(question, answer));
        while (turns.size() > 4 || size() > 24_000) turns.remove(0);
    }
    private int size() { return turns.stream().mapToInt(t -> t.question().length() + t.answer().length()).sum(); }
}
