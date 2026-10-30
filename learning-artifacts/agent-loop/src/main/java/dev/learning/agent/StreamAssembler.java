package dev.learning.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Reassembles SSE text and tool-call fragments into one assistant turn. */
final class StreamAssembler {
    private final ObjectMapper json;
    private final Consumer<String> onText;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, CallParts> calls = new TreeMap<>();
    private String finishReason;
    private boolean done;

    StreamAssembler(ObjectMapper json, Consumer<String> onText) {
        this.json = json;
        this.onText = onText;
    }

    AssistantTurn read(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder event = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(event);
                    event.setLength(0);
                    if (done) break;
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                }
            }
            if (!event.isEmpty()) dispatch(event);
        }
        if (!done) throw new IOException("DeepSeek stream ended without [DONE]");
        if (finishReason == null) throw new IOException("DeepSeek stream has no finish reason");
        List<ToolCall> completedCalls = new ArrayList<>();
        for (CallParts call : calls.values()) completedCalls.add(call.build());
        return new AssistantTurn(content.toString(), List.copyOf(completedCalls), finishReason);
    }

    private void dispatch(StringBuilder event) throws IOException {
        if (event.isEmpty()) return;
        if ("[DONE]".contentEquals(event)) { done = true; return; }
        ChatChunk chunk = json.readValue(event.toString(), ChatChunk.class);
        if (chunk.choices() == null) return;
        for (ChunkChoice choice : chunk.choices()) {
            if (choice.finishReason() != null) finishReason = choice.finishReason();
            ChunkDelta delta = choice.delta();
            if (delta == null) continue;
            if (delta.content() != null && !delta.content().isEmpty()) {
                content.append(delta.content());
                onText.accept(delta.content());
            }
            if (delta.toolCalls() == null) continue;
            for (ToolCallDelta part : delta.toolCalls())
                calls.computeIfAbsent(part.index(), ignored -> new CallParts()).append(part);
        }
    }

    private static final class CallParts {
        private String id;
        private String type;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void append(ToolCallDelta part) {
            if (part.id() != null) id = part.id();
            if (part.type() != null) type = part.type();
            if (part.function() != null) {
                if (part.function().name() != null) name.append(part.function().name());
                if (part.function().arguments() != null) arguments.append(part.function().arguments());
            }
        }

        ToolCall build() throws IOException {
            if (id == null || name.isEmpty()) throw new IOException("incomplete streamed tool call");
            return new ToolCall(id, type == null ? "function" : type,
                    new FunctionCall(name.toString(), arguments.toString()));
        }
    }
}
