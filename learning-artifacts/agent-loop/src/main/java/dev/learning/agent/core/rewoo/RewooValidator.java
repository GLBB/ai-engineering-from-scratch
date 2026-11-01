package dev.learning.agent.core.rewoo;

import dev.learning.agent.core.planning.PlanValidationException;
import java.util.*;

public final class RewooValidator {
    private RewooValidator() {}
    public record Validated(RewooPlan plan, List<RewooPlan.Step> order, Map<String, Set<String>> dependencies) {}

    public static Validated validate(RewooPlan plan, Set<String> tools) throws PlanValidationException {
        Map<String, RewooPlan.Step> byId = new LinkedHashMap<>();
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        if (plan.steps().size() > 8) throw new PlanValidationException("too many plan nodes");
        for (RewooPlan.Step step : plan.steps()) {
            if (!step.id().matches("E[1-9][0-9]*") || byId.putIfAbsent(step.id(), step) != null) {
                throw new PlanValidationException("invalid or duplicate ID " + step.id());
            }
            if (!tools.contains(step.tool())) throw new PlanValidationException("unavailable tool " + step.tool());
            positions.put(step.id(), positions.size());
            dependencies.put(step.id(), EvidenceBinder.references(step.arguments()));
        }
        for (var entry : dependencies.entrySet()) {
            indegree.put(entry.getKey(), entry.getValue().size());
            for (String dependency : entry.getValue()) {
                if (!byId.containsKey(dependency)) throw new PlanValidationException(entry.getKey() + " references missing " + dependency);
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(positions::get));
        indegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<RewooPlan.Step> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(byId.get(id));
            for (String next : dependents.getOrDefault(id, List.of())) {
                int left = indegree.merge(next, -1, Integer::sum);
                if (left == 0) ready.add(next);
            }
        }
        if (ordered.size() != byId.size()) throw new PlanValidationException("plan contains a dependency cycle");
        if (plan.steps().isEmpty() != plan.answerEvidence().isEmpty()) throw new PlanValidationException("answerEvidence does not match empty/nonempty plan");
        for (String id : plan.answerEvidence()) {
            if (!byId.containsKey(id)) throw new PlanValidationException("missing answer evidence " + id);
        }
        return new Validated(plan, List.copyOf(ordered), Map.copyOf(dependencies));
    }
}
