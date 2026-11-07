package dev.learning.agent.core.planning;

import java.util.ArrayList;
import java.util.List;

/** Bounded, protocol-independent conversation context; never stores plans or native tool calls. */
public final class ConversationMemory {
    private final List<TaskInput.ConversationTurn> turns = new ArrayList<>();
    public ConversationMemory() {}
    public ConversationMemory(List<TaskInput.ConversationTurn> history) {
        for (var turn : history) commit(turn);
    }
    public List<TaskInput.ConversationTurn> history() { return List.copyOf(turns); }
    public TaskInput snapshot(String question) { return new TaskInput(question, turns); }
    public void commit(String question, String answer) {
        commit(new TaskInput.ConversationTurn(question, answer));
    }
    public void commit(TaskInput.ConversationTurn turn) {
        turns.add(turn);
        while (turns.size() > 4 || size() > 24_000) turns.remove(0);
    }
    private int size() {
        return turns.stream().mapToInt(t -> t.question().length() + t.answer().length()
                + t.operations().stream().mapToInt(o -> o.tool().length() + o.status().name().length() + 16).sum()).sum();
    }
}
