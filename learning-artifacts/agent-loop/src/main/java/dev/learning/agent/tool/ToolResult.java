package dev.learning.agent.tool;

import java.util.List;

public record ToolResult(ToolStatus status, String content, String errorCode,
                         boolean executed, boolean hasValue, boolean truncated, List<ToolSource> sources) {
    public ToolResult { sources = List.copyOf(sources); }
    public ToolResult(ToolStatus status, String content, String errorCode,
                      boolean executed, boolean hasValue, boolean truncated) {
        this(status, content, errorCode, executed, hasValue, truncated, List.of());
    }
}
