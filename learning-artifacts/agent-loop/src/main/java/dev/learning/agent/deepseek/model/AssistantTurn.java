package dev.learning.agent.deepseek.model;

import java.util.List;

public record AssistantTurn(String content, List<ToolCall> calls, String finishReason) {}
