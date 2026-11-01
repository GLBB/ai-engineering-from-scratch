package dev.learning.agent.core;


public record ToolResult(ToolStatus status, String content, String errorCode,
                         boolean executed, boolean hasValue, boolean truncated) {}
