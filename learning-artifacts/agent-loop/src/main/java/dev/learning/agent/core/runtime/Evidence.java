package dev.learning.agent.core.runtime;

import dev.learning.agent.tool.ToolResult;

public record Evidence(String id, String stepId, String tool,
                       String boundArgumentsJson, ToolResult result, long durationMs) {}
