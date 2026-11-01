package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ModelSettings;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public record EngineRequest(List<ChatMessage> transcript, ModelSettings settings,
                            Set<String> allowedTools, int maxModelCalls,
                            int maxToolAttempts, boolean publishText, RunScope scope,
                            Consumer<List<ChatMessage>> onCheckpoint) {
    public EngineRequest(List<ChatMessage> transcript, ModelSettings settings, Set<String> allowedTools,
                         int maxModelCalls, int maxToolAttempts, boolean publishText, RunScope scope) {
        this(transcript, settings, allowedTools, maxModelCalls, maxToolAttempts, publishText, scope, ignored -> {});
    }
    public EngineRequest {
        transcript = List.copyOf(transcript);
        allowedTools = Set.copyOf(allowedTools);
        if (maxModelCalls < 1 || maxToolAttempts < 0) throw new IllegalArgumentException("invalid engine limits");
    }
}
