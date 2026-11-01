package dev.learning.agent.core.planexecute;

import dev.learning.agent.support.json.StrictJson;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict JSON boundary and graph validation; application state remains typed. */
public final class GoalPlans {
    private GoalPlans() {}
    public static GoalPlan parse(String text) throws IOException {
        JsonNode node = StrictJson.object(text);
        StrictJson.fields(node, "version", "revision", "steps");
        if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToInt() || node.path("version").intValue() != 1
                || !node.path("revision").isIntegralNumber() || !node.path("revision").canConvertToInt() || node.path("revision").intValue() != 0) {
            throw new IOException("initial plan requires version 1 and revision 0");
        }
        return new GoalPlan(1, 0, steps(node.get("steps")));
    }
    public static List<GoalStep> steps(JsonNode nodes) throws IOException {
        if (nodes == null || !nodes.isArray() || nodes.size() > 8) throw new IOException("expected at most 8 steps");
        List<GoalStep> steps = new ArrayList<>();
        for (JsonNode step : nodes) {
            if (!step.isObject()) throw new IOException("step must be an object");
            StrictJson.fields(step, "id", "goal", "successCriteria", "minEvidence", "allowedTools", "dependsOn");
            JsonNode minimum = step.path("minEvidence");
            if (!minimum.isIntegralNumber() || !minimum.canConvertToInt() || minimum.intValue() < 0 || minimum.intValue() > 8)
                throw new IOException("minEvidence must be an integer in 0..8");
            steps.add(new GoalStep(StrictJson.text(step, "id"), StrictJson.text(step, "goal"),
                    StrictJson.text(step, "successCriteria"), minimum.intValue(),
                    StrictJson.strings(step, "allowedTools"), StrictJson.strings(step, "dependsOn")));
        }
        return List.copyOf(steps);
    }
    public static GoalPlan validate(GoalPlan plan, Set<String> tools) throws IOException {
        Set<String> ids = new HashSet<>();
        for (GoalStep step : plan.steps()) {
            if (!step.id().matches("S[1-9][0-9]*") || !ids.add(step.id())) throw new IOException("invalid/duplicate step ID");
            if (!tools.containsAll(step.allowedTools())) throw new IOException("tool scope exceeds registry");
        }
        Set<String> resolved = new HashSet<>();
        while (resolved.size() < ids.size()) {
            int before = resolved.size();
            for (GoalStep step : plan.steps()) {
                if (!ids.containsAll(step.dependsOn())) throw new IOException("unknown dependency");
                if (resolved.containsAll(step.dependsOn())) resolved.add(step.id());
            }
            if (before == resolved.size()) throw new IOException("cyclic step dependencies");
        }
        return plan;
    }
    public static ReplanDecision decision(String text) throws IOException {
        JsonNode node = StrictJson.object(text);
        String decision = StrictJson.text(node, "decision");
        String reason = StrictJson.text(node, "reason");
        return switch (decision) {
            case "CONTINUE" -> { StrictJson.fields(node, "decision", "reason"); yield new ReplanDecision.ContinueDecision(reason); }
            case "STOP" -> { StrictJson.fields(node, "decision", "reason"); yield new ReplanDecision.StopDecision(reason); }
            case "REVISE" -> {
                StrictJson.fields(node, "decision", "reason", "remainingSteps");
                yield new ReplanDecision.ReviseDecision(reason, steps(node.get("remainingSteps")));
            }
            case "FINISH" -> {
                StrictJson.fields(node, "decision", "reason", "status", "answerEvidence");
                String status = StrictJson.text(node, "status");
                if (!Set.of("COMPLETE", "PARTIAL").contains(status)) throw new IOException("invalid finish status");
                yield new ReplanDecision.FinishDecision(reason, status, StrictJson.strings(node, "answerEvidence"));
            }
            default -> throw new IOException("unknown replanning decision");
        };
    }
}
