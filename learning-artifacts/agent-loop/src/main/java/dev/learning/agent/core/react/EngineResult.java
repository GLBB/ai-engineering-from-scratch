package dev.learning.agent.core.react;

import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatMessage;

import java.util.List;

public record EngineResult(List<ChatMessage> transcript, AssistantTurn finalTurn,
                           EngineStop stop) {
    public EngineResult { transcript = List.copyOf(transcript); }
}
