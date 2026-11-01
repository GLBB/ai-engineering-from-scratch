package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.AssistantTurn;
import java.io.IOException;
import java.util.function.Consumer;

@FunctionalInterface
public interface ChatModel {
    default String protocol() { return "Chat Completions"; }
    AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control)
            throws IOException, InterruptedException;
}
