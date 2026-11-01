package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.deepseek.model.ToolSpec;
import java.util.List;

public record ModelRequest(List<ChatMessage> messages, ModelSettings settings,
                           List<ToolSpec> tools, OutputMode outputMode,
                           int maxOutputTokens, ModelUse use) {
    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (settings == null || outputMode == null || use == null) {
            throw new IllegalArgumentException("model request settings are required");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 4096) {
            throw new IllegalArgumentException("output token limit must be 1..4096");
        }
    }
}
