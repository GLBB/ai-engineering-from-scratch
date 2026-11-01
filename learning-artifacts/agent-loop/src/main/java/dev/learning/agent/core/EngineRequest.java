package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ModelSettings;
import java.util.List;
import java.util.Set;

public record EngineRequest(List<ChatMessage> transcript, ModelSettings settings,
                            Set<String> allowedTools, int maxModelCalls,
                            int maxToolAttempts, boolean publishText, RunScope scope) {
    public EngineRequest {
        transcript = List.copyOf(transcript);
        allowedTools = Set.copyOf(allowedTools);
        if (maxModelCalls < 1 || maxToolAttempts < 0) throw new IllegalArgumentException("invalid engine limits");
    }
}
