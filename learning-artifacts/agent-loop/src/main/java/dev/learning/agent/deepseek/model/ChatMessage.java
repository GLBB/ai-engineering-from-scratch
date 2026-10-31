package dev.learning.agent.deepseek.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content,
                   @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                   @JsonProperty("tool_call_id") String toolCallId,
                   @JsonProperty("reasoning_content") String reasoningContent,
                   @JsonIgnore List<AssistantBlock> blocks) {
    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                       String reasoningContent) {
        this(role, content, toolCalls, toolCallId, reasoningContent, null);
    }
    public static ChatMessage system(String text) {
        return new ChatMessage("system", text, null, null, null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text, null, null, null);
    }

    public static ChatMessage assistant(String text, List<ToolCall> calls) {
        return assistant(text, calls, null);
    }

    public static ChatMessage assistant(String text, List<ToolCall> calls, String reasoningContent) {
        return new ChatMessage("assistant", text, calls, null, reasoningContent);
    }

    public static ChatMessage assistant(AssistantTurn turn) {
        List<ToolCall> calls = turn.calls().isEmpty() ? null : turn.calls();
        return new ChatMessage("assistant", turn.content(), calls, null, turn.reasoningContent(), turn.blocks());
    }

    public static ChatMessage tool(String id, String result) {
        return new ChatMessage("tool", result, null, id, null);
    }
}
