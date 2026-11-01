package dev.learning.agent.core;


public record Evidence(String id, String stepId, String tool,
                       String boundArgumentsJson, ToolResult result, long durationMs) {}
