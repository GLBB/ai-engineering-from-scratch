package dev.learning.agent.model;

import dev.learning.agent.core.runtime.RunControl;

import java.io.IOException;
import java.util.function.Consumer;

@FunctionalInterface
public interface ChatModel {
    default String protocol() { return "Chat Completions"; }
    AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control)
            throws IOException, InterruptedException;
    default AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control, ModelStreamObserver observer)
            throws IOException, InterruptedException {
        return stream(request, part -> { if (!part.isEmpty()) observer.chunk(true); onText.accept(part); }, control);
    }
}
