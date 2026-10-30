package dev.learning.agent.deepseek.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content,
                   @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                   @JsonProperty("tool_call_id") String toolCallId) {
    public static ChatMessage system(String text) { return new ChatMessage("system", text, null, null); }
    public static ChatMessage user(String text) { return new ChatMessage("user", text, null, null); }
    public static ChatMessage assistant(String text, List<ToolCall> calls) { return new ChatMessage("assistant", text, calls, null); }
    public static ChatMessage tool(String id, String result) { return new ChatMessage("tool", result, null, id); }
}
