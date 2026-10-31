package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.deepseek.model.ToolSpec;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** The one model operation needed by the agent loop. */
@FunctionalInterface
public interface ChatModel {
    default String protocol() { return "Chat Completions"; }

    AssistantTurn stream(List<ChatMessage> messages, List<ToolSpec> tools,
                         Consumer<String> onText, ModelSettings settings)
            throws IOException, InterruptedException;
}
