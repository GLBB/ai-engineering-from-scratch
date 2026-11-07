package dev.learning.agent.core.runtime;

import dev.learning.agent.model.ModelUse;
import dev.learning.agent.tool.ToolResult;

public interface RunBudget {
    void claimModel(ModelUse use);

    void claimToolAttempt();

    void recordToolResult(ToolResult result);

    void claimStep();

    void claimRevision();

    boolean claimFormatRepair();

    boolean canStartStep();

    RunCounters snapshot();
}
