package dev.learning.agent.model;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.core.runtime.TraceEvent;

import java.io.IOException;
import java.util.function.Consumer;

/** Every actual model attempt is counted and traced through this boundary. */
public final class DefaultModelGateway implements ModelGateway {
    private final ChatModel client;
    public DefaultModelGateway(ChatModel client) { this.client = client; }
    public String protocol() { return client.protocol(); }

    public AssistantTurn call(ModelRequest request, RunScope scope, Consumer<String> onText, RunContext context)
            throws IOException, InterruptedException {
        context.control().check();
        context.budget().claimModel(request.use());
        int round = context.budget().snapshot().modelCalls();
        String id = "model-" + context.requestId() + "-" + round;
        long at = System.currentTimeMillis();
        long start = System.nanoTime();
        int count = request.messages().size();
        context.trace(scope, TraceEvent.modelStarted(id, round, at, count));
        AssistantTurn answer;
        try {
            int[] length = {0};
            answer = client.stream(request, part -> {
                if ((length[0] += part.length()) > 16_000) throw new IllegalStateException("model output too large");
                onText.accept(part);
            }, context.control());
            context.control().check();
            validate(answer);
        } catch (IOException | InterruptedException | RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            context.trace(scope, TraceEvent.modelFailed(id, round, at, elapsed(start), count, reason));
            throw e;
        }
        context.trace(scope, TraceEvent.modelFinished(id, round, at, elapsed(start), count,
                answer.finishReason(), answer.calls().size(), answer.content()).withUsage(answer.usage()));
        if (answer.reasoningContent() != null && !answer.reasoningContent().isBlank()) {
            context.trace(scope, TraceEvent.reasoning("reasoning-" + id, round, at, answer.reasoningContent()));
        }
        if (!answer.content().isBlank()) {
            context.trace(scope, TraceEvent.assistant("assistant-" + id, round, at, answer.content()));
        }
        return answer;
    }

    static void validate(AssistantTurn answer) throws IOException {
        if (answer == null || answer.calls() == null || answer.content() == null) throw new IOException("empty model response");
        if (answer.calls().isEmpty() && !"stop".equals(answer.finishReason())) {
            throw new IOException("DeepSeek stopped unexpectedly: " + answer.finishReason());
        }
        if (!answer.calls().isEmpty() && !"tool_calls".equals(answer.finishReason())) {
            throw new IOException("DeepSeek returned tools with unexpected finish reason: " + answer.finishReason());
        }
        int size = answer.content().length() + (answer.reasoningContent() == null ? 0 : answer.reasoningContent().length());
        for (var call : answer.calls()) {
            if (call == null || call.function() == null || call.function().arguments() == null || call.function().name() == null)
                throw new IOException("malformed tool call");
            size += call.function().arguments().length();
        }
        if (size > 16_000) throw new IOException("model output too large");
    }

    static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
