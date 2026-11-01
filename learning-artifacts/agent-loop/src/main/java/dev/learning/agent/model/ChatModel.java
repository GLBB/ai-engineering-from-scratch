package dev.learning.agent.model;

import dev.learning.agent.core.runtime.RunControl;

import java.io.IOException;
import java.util.function.Consumer;

@FunctionalInterface
public interface ChatModel {
    default String protocol() { return "Chat Completions"; }
    AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control)
            throws IOException, InterruptedException;
}
