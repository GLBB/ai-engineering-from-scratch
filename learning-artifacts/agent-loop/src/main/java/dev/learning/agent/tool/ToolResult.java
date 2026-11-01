package dev.learning.agent.tool;

public record ToolResult(ToolStatus status, String content, String errorCode,
                         boolean executed, boolean hasValue, boolean truncated) {}
