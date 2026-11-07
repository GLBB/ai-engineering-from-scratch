package dev.learning.agent.core.planning;

import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.tool.ToolStatus;

import java.util.List;

public record TaskInput(String question, List<ConversationTurn> history) {
    public TaskInput {
        history = List.copyOf(history);
    }

    public record ConversationTurn(String question, String answer, ExecutionStatus status,
        List<ToolOutcome> operations) {
        public ConversationTurn {
            operations = List.copyOf(operations);
        }

        public ConversationTurn(String question, String answer) {
            this(question, answer, ExecutionStatus.COMPLETE, List.of());
        }
    }

    /** Historical attempts, not reusable evidence or permission to repeat an operation. */
    public record ToolOutcome(String tool, ToolStatus status, boolean executed) {
    }
}
