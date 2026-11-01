package dev.learning.agent.core.planexecute;

import java.util.List;
public record GoalPlan(int version, int revision, List<GoalStep> steps) {
    public GoalPlan { steps = List.copyOf(steps); }
}
