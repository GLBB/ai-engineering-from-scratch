package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatMessage;
import java.util.List;

public record EngineResult(List<ChatMessage> transcript, AssistantTurn finalTurn,
                           EngineStop stop) {
    public EngineResult { transcript = List.copyOf(transcript); }
}
