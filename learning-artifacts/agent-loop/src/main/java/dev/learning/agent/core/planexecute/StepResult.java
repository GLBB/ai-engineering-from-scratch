package dev.learning.agent.core.planexecute;

import java.util.List;
public record StepResult(String stepId, Status status, String summary, List<String> evidenceIds) {
    public enum Status { COMPLETE, INCOMPLETE, FAILED, DENIED, CANCELLED }
    public StepResult { evidenceIds = List.copyOf(evidenceIds); }
}
