package dev.learning.agent.core;

/** A local execution limit; it never implies that the task succeeded. */
public final class BudgetExceededException extends IllegalStateException {
    public BudgetExceededException(String resource) {
        super("budget exhausted: " + resource);
    }
}
