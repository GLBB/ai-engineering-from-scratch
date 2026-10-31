package dev.learning.agent.deepseek.model;

import java.util.List;

public record AssistantTurn(String content, List<ToolCall> calls, String finishReason,
                            String reasoningContent, List<AssistantBlock> blocks) {
    public AssistantTurn(String content, List<ToolCall> calls, String finishReason, String reasoningContent) {
        this(content, calls, finishReason, reasoningContent, null);
    }
    public AssistantTurn(String content, List<ToolCall> calls, String finishReason) {
        this(content, calls, finishReason, null);
    }
}
