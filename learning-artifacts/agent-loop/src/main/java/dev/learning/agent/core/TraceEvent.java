package dev.learning.agent.core;

import dev.learning.agent.deepseek.model.ModelSettings;

/** One observed step in a user request. A later event with the same ID completes it. */
public record TraceEvent(
        String id,
        String kind,
        String status,
        int round,
        long startedAtMs,
        long durationMs,
        String label,
        String input,
        String output, String requestId, AgentMode mode, String phase, String stepId, int revision, dev.learning.agent.deepseek.model.TokenUsage usage) {

    public TraceEvent(String id, String kind, String status, int round, long startedAtMs,
                      long durationMs, String label, String input, String output) {
        this(id, kind, status, round, startedAtMs, durationMs, label, input, output, null, null, null, null, 0, null);
    }

    public TraceEvent scoped(String requestId, AgentMode mode, RunScope scope) {
        return new TraceEvent(id, kind, status, round, startedAtMs, durationMs, label, input, output,
                requestId, mode, scope.phase(), scope.stepId(), scope.revision(), usage);
    }

    public TraceEvent withUsage(dev.learning.agent.deepseek.model.TokenUsage value) {
        return new TraceEvent(id, kind, status, round, startedAtMs, durationMs, label, input, output,
                requestId, mode, phase, stepId, revision, value);
    }

    public static TraceEvent user(String id, String prompt, long atMs) {
        return new TraceEvent(id, "user", "complete", 0, atMs, 0, "用户输入", prompt, null);
    }

    public static TraceEvent settings(String id, ModelSettings settings, String protocol, long atMs) {
        return new TraceEvent(id, "settings", "complete", 0, atMs, 0,
                "模型设置", settings.summary() + " · API " + protocol, null);
    }

    public static TraceEvent reasoning(String id, int round, long atMs, String content) {
        return new TraceEvent(id, "reasoning", "complete", round, atMs, 0,
                "模型思考", null, content);
    }

    public static TraceEvent modelStarted(String id, int round, long atMs, int messageCount) {
        return new TraceEvent(id, "model", "running", round, atMs, 0,
                "模型请求 " + round, "历史消息：" + messageCount + " 条", null);
    }

    public static TraceEvent modelFinished(String id, int round, long atMs, long durationMs,
                                           int messageCount, String finishReason, int toolCount,
                                           String content) {
        String result = "结束原因：" + finishReason + "；工具调用：" + toolCount + " 次";
        if (content != null && !content.isBlank()) {
            result += "\n\n" + content;
        }
        return new TraceEvent(id, "model", "complete", round, atMs, durationMs,
                "模型请求 " + round, "历史消息：" + messageCount + " 条", result);
    }

    public static TraceEvent modelFailed(String id, int round, long atMs, long durationMs,
                                         int messageCount, String reason) {
        return new TraceEvent(id, "model", "error", round, atMs, durationMs,
                "模型请求 " + round, "历史消息：" + messageCount + " 条", reason);
    }

    public static TraceEvent toolStarted(String id, int round, long atMs,
                                         String name, String arguments) {
        return new TraceEvent(id, "tool", "running", round, atMs, 0,
                name, arguments, null);
    }

    public static TraceEvent toolFinished(String id, int round, long atMs, long durationMs,
                                          String name, String arguments, String result) {
        return new TraceEvent(id, "tool", "complete", round, atMs, durationMs,
                name, arguments, result);
    }

    public static TraceEvent assistant(String id, int round, long atMs, String content) {
        return new TraceEvent(id, "assistant", "complete", round, atMs, 0,
                "助手消息", null, content);
    }
}
