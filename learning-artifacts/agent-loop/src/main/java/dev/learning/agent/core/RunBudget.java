package dev.learning.agent.core;


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
