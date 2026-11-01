package dev.learning.agent.core.runtime;

public record RunCounters(int modelCalls, int toolAttempts, int toolsExecuted,
                          int stepAttempts, int planRevisions) {}
