package dev.learning.agent;

import java.util.List;

record AssistantTurn(String content, List<ToolCall> calls, String finishReason) {}
