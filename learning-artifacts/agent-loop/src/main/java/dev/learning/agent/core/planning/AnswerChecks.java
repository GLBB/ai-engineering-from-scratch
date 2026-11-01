package dev.learning.agent.core.planning;

import dev.learning.agent.core.*;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

public final class AnswerChecks {
    private static final Pattern CITATION = Pattern.compile("\\[(E[1-9][0-9]*)]");
    private AnswerChecks() {}
    public static void citations(String answer, List<String> required, EvidenceStore evidence) throws PlanValidationException {
        HashSet<String> cited = new HashSet<>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            try { evidence.requireUsable(matcher.group(1)); }
            catch (IllegalArgumentException e) { throw new PlanValidationException("answer cites unusable " + matcher.group(1)); }
            cited.add(matcher.group(1));
        }
        for (String id : required) {
            if (usable(evidence, id) && !cited.contains(id)) throw new PlanValidationException("answer omitted citation " + id);
        }
    }
    public static boolean usable(EvidenceStore store, String id) {
        try { store.requireUsable(id); return true; } catch (IllegalArgumentException e) { return false; }
    }
    public static ExecutionStatus status(List<String> required, EvidenceStore store) {
        if (store.snapshot().isEmpty() && required.isEmpty()) return ExecutionStatus.COMPLETE;
        boolean any = store.snapshot().stream().anyMatch(e -> usable(store, e.id()));
        if (!any) return ExecutionStatus.FAILED;
        if (required.stream().allMatch(id -> usable(store, id))
                && store.snapshot().stream().allMatch(e -> usable(store, e.id()))) return ExecutionStatus.COMPLETE;
        return ExecutionStatus.PARTIAL;
    }
}
