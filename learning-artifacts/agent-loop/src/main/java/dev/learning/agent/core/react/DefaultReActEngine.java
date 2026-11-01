package dev.learning.agent.core.react;

import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.model.ChatMessage;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.ModelUse;
import dev.learning.agent.model.OutputMode;
import dev.learning.agent.model.ToolCall;
import dev.learning.agent.tool.DefaultToolExecutionService;
import dev.learning.agent.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Stateless feedback loop used for whole conversations and individual P&E steps. */
public final class DefaultReActEngine implements ReActEngine {
    private final ToolRegistry tools;
    public DefaultReActEngine(ToolRegistry tools) { this.tools = tools; }

    public EngineResult execute(EngineRequest request, RunContext context) throws IOException, InterruptedException {
        List<ChatMessage> transcript = new ArrayList<>(request.transcript());
        request.onCheckpoint().accept(List.copyOf(transcript));
        int attempted = 0;
        var definitions = tools.definitions().stream()
                .filter(tool -> request.allowedTools().contains(tool.function().name())).toList();
        for (int round = 0; round < request.maxModelCalls(); round++) {
            context.control().check();
            var modelRequest = new ModelRequest(transcript, request.settings(), definitions, OutputMode.TEXT, 4096, ModelUse.EXECUTOR);
            var answer = context.model().call(modelRequest, request.scope(),
                    part -> { if (request.publishText()) context.emit("text", part); }, context);
            validateIds(answer.calls());
            if (answer.calls().isEmpty()) {
                transcript.add(ChatMessage.assistant(answer));
                request.onCheckpoint().accept(List.copyOf(transcript));
                return new EngineResult(transcript, answer, EngineStop.FINAL_RESPONSE);
            }
            List<ChatMessage> results = new ArrayList<>();
            for (ToolCall call : answer.calls()) {
                String id = context.evidence().nextId();
                Evidence evidence;
                boolean cancelled = Thread.currentThread().isInterrupted();
                try { context.control().check(); } catch (InterruptedException e) { cancelled = true; }
                if (cancelled || attempted >= request.maxToolAttempts()) {
                    evidence = DefaultToolExecutionService.skipped(call.function().name(), call.function().arguments(),
                            id, request.scope(), context, cancelled ? "run cancelled; not executed" : "local tool limit; not executed");
                } else {
                    attempted++;
                    evidence = context.tools().invokeNative(call, id, request.allowedTools(), request.scope(), context);
                }
                results.add(ChatMessage.tool(call.id(), DefaultToolExecutionService.modelContent(evidence)));
            }
            transcript.add(ChatMessage.assistant(answer));
            transcript.addAll(results);
            request.onCheckpoint().accept(List.copyOf(transcript));
            context.control().check();
        }
        return new EngineResult(transcript, null, EngineStop.LOCAL_BUDGET);
    }

    private static void validateIds(List<ToolCall> calls) throws IOException {
        HashSet<String> ids = new HashSet<>();
        for (ToolCall call : calls) {
            if (call.id() == null || call.id().isBlank() || !ids.add(call.id()) || call.function() == null) {
                throw new IOException("invalid or duplicate tool call ID");
            }
        }
    }
}
