package dev.learning.agent.core.runtime;

import java.util.List;

public interface EvidenceStore {
    String nextId();

    void put(Evidence evidence);

    Evidence requireUsable(String id);

    List<Evidence> snapshot();
}
