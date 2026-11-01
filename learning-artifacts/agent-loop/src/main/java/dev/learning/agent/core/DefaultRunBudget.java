package dev.learning.agent.core;

/** One budget shared by every model call and sub-step in a user request. */
public final class DefaultRunBudget implements RunBudget {
    private final AgentMode mode;
    private final int maxModels;
    private final int maxTools;
    private int models;
    private int tools;
    private int executed;
    private int steps;
    private int revisions;
    private boolean repaired;
    private boolean finalized;

    public DefaultRunBudget(AgentMode mode) {
        this.mode = mode;
        maxModels = switch (mode) { case REACT -> 8; case REWOO -> 3; case PLAN_EXECUTE -> 35; };
        maxTools = switch (mode) { case REACT -> 16; case REWOO -> 8; case PLAN_EXECUTE -> 12; };
    }

    public synchronized void claimModel(ModelUse use) {
        int reserved = 0;
        if (mode != AgentMode.REACT && use != ModelUse.FINALIZER && !finalized) reserved = 1;
        if (mode == AgentMode.PLAN_EXECUTE && use == ModelUse.EXECUTOR) reserved++;
        if (models >= maxModels - reserved || (use == ModelUse.FINALIZER && finalized)) {
            throw new BudgetExceededException("model calls");
        }
        models++;
        if (use == ModelUse.FINALIZER) finalized = true;
    }

    public synchronized void claimToolAttempt() {
        if (tools >= maxTools) throw new BudgetExceededException("tool attempts");
        tools++;
    }

    public synchronized void recordToolResult(ToolResult result) {
        if (result.executed()) executed++;
    }

    public synchronized void claimStep() {
        if (steps >= 8) throw new BudgetExceededException("step attempts");
        steps++;
    }

    public synchronized void claimRevision() {
        if (mode != AgentMode.PLAN_EXECUTE || revisions >= 2) {
            throw new BudgetExceededException("plan revisions");
        }
        revisions++;
    }

    public synchronized boolean claimFormatRepair() {
        if (mode == AgentMode.REACT || repaired) return false;
        repaired = true;
        return true;
    }

    public synchronized boolean canStartStep() {
        return mode == AgentMode.PLAN_EXECUTE && steps < 8 && tools < maxTools && maxModels - models >= 3;
    }

    public synchronized RunCounters snapshot() {
        return new RunCounters(models, tools, executed, steps, revisions);
    }
}
