package dev.learning.agent.core.planexecute;

import java.util.List;

public record GoalStep(String id, String goal, String successCriteria, int minEvidence, List<String> allowedTools,
    List<String> dependsOn) {
    public GoalStep {
        allowedTools = List.copyOf(allowedTools);
        dependsOn = List.copyOf(dependsOn);
    }
}
