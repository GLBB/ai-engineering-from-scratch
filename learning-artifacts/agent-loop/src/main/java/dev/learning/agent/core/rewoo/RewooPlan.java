package dev.learning.agent.core.rewoo;

import dev.learning.agent.tool.ToolInput;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RewooPlan(int version, List<Step> steps, List<String> answerEvidence) {
    public RewooPlan { steps = List.copyOf(steps); answerEvidence = List.copyOf(answerEvidence); }
    public record Step(String id, String purpose, ToolInput arguments) {
        @JsonProperty("tool") public String tool() { return arguments.toolName(); }
    }
}
