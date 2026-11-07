package dev.learning.agent.core.planexecute;

import java.util.List;

public sealed interface ReplanDecision {
    String reason();

    record ContinueDecision(String reason) implements ReplanDecision {
    }

    record ReviseDecision(String reason, List<GoalStep> remainingSteps) implements ReplanDecision {
        public ReviseDecision {
            remainingSteps = List.copyOf(remainingSteps);
        }
    }

    record FinishDecision(String reason, String status, List<String> answerEvidence) implements ReplanDecision {
        public FinishDecision {
            answerEvidence = List.copyOf(answerEvidence);
        }
    }

    record StopDecision(String reason) implements ReplanDecision {
    }
}
