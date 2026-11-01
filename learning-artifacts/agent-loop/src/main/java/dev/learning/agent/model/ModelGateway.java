package dev.learning.agent.model;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;

import java.io.IOException;
import java.util.function.Consumer;

public interface ModelGateway {
    default String protocol() { return "model"; }
    AssistantTurn call(ModelRequest request, RunScope scope,
                       Consumer<String> onText, RunContext context)
            throws IOException, InterruptedException;
}
