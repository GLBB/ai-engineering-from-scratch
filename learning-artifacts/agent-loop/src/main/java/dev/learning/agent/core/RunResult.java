package dev.learning.agent.core;

import dev.learning.agent.core.runtime.RunCounters;

public record RunResult(ExecutionStatus status, CompletionCheck completionCheck, String answer, String errorCode,
    RunCounters counters) {
}
