package dev.learning.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ChatMessage(String role, String content,
                   @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                   @JsonProperty("tool_call_id") String toolCallId) {
    static ChatMessage system(String text) { return new ChatMessage("system", text, null, null); }
    static ChatMessage user(String text) { return new ChatMessage("user", text, null, null); }
    static ChatMessage assistant(String text, List<ToolCall> calls) { return new ChatMessage("assistant", text, calls, null); }
    static ChatMessage tool(String id, String result) { return new ChatMessage("tool", result, null, id); }
}
