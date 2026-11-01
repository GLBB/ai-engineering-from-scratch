package dev.learning.agent.core;

import java.io.IOException;

public interface ReActEngine {
    EngineResult execute(EngineRequest request, RunContext context)
            throws IOException, InterruptedException;
}
