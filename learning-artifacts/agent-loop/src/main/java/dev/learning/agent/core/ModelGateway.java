package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.AssistantTurn;
import java.io.IOException;
import java.util.function.Consumer;

public interface ModelGateway {
    AssistantTurn call(ModelRequest request, RunScope scope,
                       Consumer<String> onText, RunContext context)
            throws IOException, InterruptedException;
}
