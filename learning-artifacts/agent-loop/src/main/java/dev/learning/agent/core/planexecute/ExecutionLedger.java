package dev.learning.agent.core.planexecute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Append-only attempts and versions preserve failed operations through replanning. */
public final class ExecutionLedger {
    private final List<GoalPlan> versions = new ArrayList<>();
    private final Map<String, StepResult> attempts = new LinkedHashMap<>();
    private final Set<String> usedIds = new HashSet<>();
    private final Set<String> superseded = new HashSet<>();
    public ExecutionLedger(GoalPlan initial) { commit(initial); }
    public GoalPlan current() { return versions.get(versions.size() - 1); }
    public List<GoalPlan> versions() { return List.copyOf(versions); }
    public List<StepResult> attempts() { return List.copyOf(attempts.values()); }
    public Set<String> superseded() { return Set.copyOf(superseded); }
    public void record(StepResult result) {
        if (attempts.putIfAbsent(result.stepId(), result) != null) throw new IllegalStateException("step already attempted");
    }
    public boolean completed(String id) {
        StepResult result = attempts.get(id);
        return result != null && result.status() == StepResult.Status.COMPLETE;
    }
    public boolean complete() { return current().steps().stream().allMatch(step -> completed(step.id())); }
    public GoalStep next() {
        return current().steps().stream().filter(step -> !attempts.containsKey(step.id()))
                .filter(step -> step.dependsOn().stream().allMatch(this::completed)).findFirst().orElse(null);
    }
    public GoalPlan revision(List<GoalStep> remaining, Set<String> tools) throws IOException {
        List<GoalStep> retained = current().steps().stream().filter(step -> completed(step.id())).toList();
        for (GoalStep step : remaining) {
            if (usedIds.contains(step.id())) throw new IOException("historical step ID cannot be reused: " + step.id());
        }
        if (fingerprints(remaining).equals(fingerprints(current().steps().stream()
                .filter(step -> !completed(step.id())).toList()))) throw new IOException("revision makes no progress");
        List<GoalStep> combined = new ArrayList<>(retained);
        combined.addAll(remaining);
        if (combined.size() > 8) throw new IOException("active plan exceeds 8 steps");
        return GoalPlans.validate(new GoalPlan(1, current().revision() + 1, combined), tools);
    }
    public void commit(GoalPlan plan) {
        if (!versions.isEmpty()) for (GoalStep old : current().steps()) {
            if (!attempts.containsKey(old.id()) && plan.steps().stream().noneMatch(s -> s.id().equals(old.id())))
                superseded.add(old.id());
        }
        versions.add(plan);
        plan.steps().forEach(step -> usedIds.add(step.id()));
    }
    private static List<String> fingerprints(List<GoalStep> steps) {
        return steps.stream().map(step -> step.goal().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim()
                + ":" + step.allowedTools().stream().sorted().toList()).sorted().toList();
    }
}
