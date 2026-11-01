package dev.learning.agent.core;

import dev.learning.agent.core.runtime.RunContext;

import java.io.IOException;

public interface AgentRunner {
    RunResult run(ExecutionRequest request, RunContext context)
            throws IOException, InterruptedException;
}
