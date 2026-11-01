package dev.learning.agent.core.runtime;

import dev.learning.agent.tool.ToolStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only evidence for a single run; failed values cannot bind arguments. */
public final class InMemoryEvidenceStore implements EvidenceStore {
    private final Map<String, Evidence> entries = new LinkedHashMap<>();
    private int sequence;

    public String nextId() {
        String id;
        do { id = "E" + ++sequence; } while (entries.containsKey(id));
        return id;
    }

    public void put(Evidence evidence) {
        if (entries.putIfAbsent(evidence.id(), evidence) != null) {
            throw new IllegalArgumentException("duplicate evidence: " + evidence.id());
        }
    }

    public Evidence requireUsable(String id) {
        Evidence found = entries.get(id);
        if (found == null || found.result().status() != ToolStatus.SUCCESS
                || !found.result().hasValue() || found.result().truncated()) {
            throw new IllegalArgumentException("unusable evidence: " + id);
        }
        return found;
    }

    public List<Evidence> snapshot() { return List.copyOf(entries.values()); }
}
