package dev.learning.agent.core;


public record RunCounters(int modelCalls, int toolAttempts, int toolsExecuted,
                          int stepAttempts, int planRevisions) {}
