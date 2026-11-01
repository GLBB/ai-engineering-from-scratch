package dev.learning.agent.core;


public record RunResult(ExecutionStatus status, CompletionCheck completionCheck,
                        String answer, String errorCode, RunCounters counters) {}
