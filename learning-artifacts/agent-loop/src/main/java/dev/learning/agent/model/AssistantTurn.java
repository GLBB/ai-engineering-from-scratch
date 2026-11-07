package dev.learning.agent.model;

import java.util.List;

public record AssistantTurn(String content, List<ToolCall> calls, String finishReason,
                            String reasoningContent, List<AssistantBlock> blocks, TokenUsage usage, ResponseMetadata metadata) {
    public record ResponseMetadata(String id, String model) {}
    public AssistantTurn(String content, List<ToolCall> calls, String finishReason,
                         String reasoningContent, List<AssistantBlock> blocks, TokenUsage usage) {
        this(content, calls, finishReason, reasoningContent, blocks, usage, null);
    }
    public AssistantTurn(String content, List<ToolCall> calls, String finishReason,
                         String reasoningContent, List<AssistantBlock> blocks) {
        this(content, calls, finishReason, reasoningContent, blocks, null);
    }
    public AssistantTurn(String content, List<ToolCall> calls, String finishReason, String reasoningContent) {
        this(content, calls, finishReason, reasoningContent, null);
    }
    public AssistantTurn(String content, List<ToolCall> calls, String finishReason) {
        this(content, calls, finishReason, null);
    }
}
