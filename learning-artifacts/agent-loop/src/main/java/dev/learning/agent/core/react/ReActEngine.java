package dev.learning.agent.core.react;

import dev.learning.agent.core.runtime.RunContext;

import java.io.IOException;

public interface ReActEngine {
    EngineResult execute(EngineRequest request, RunContext context)
            throws IOException, InterruptedException;
}
