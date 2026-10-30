package dev.learning.agent.deepseek;

import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatChunk;
import dev.learning.agent.deepseek.model.ChatChunk.ChunkChoice;
import dev.learning.agent.deepseek.model.ChatChunk.ChunkDelta;
import dev.learning.agent.deepseek.model.ChatChunk.ToolCallDelta;
import dev.learning.agent.deepseek.model.ToolCall;
import dev.learning.agent.deepseek.model.ToolCall.FunctionCall;

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
import java.util.logging.Logger;

/** Reassembles SSE text and tool-call fragments into one assistant turn. */
public final class StreamAssembler {
    private static final Logger LOG = Logger.getLogger(StreamAssembler.class.getName());
    private final ObjectMapper json;
    private final Consumer<String> onText;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, CallParts> calls = new TreeMap<>();
    private String finishReason;
    private boolean done;
    private int chunks;

    public StreamAssembler(ObjectMapper json, Consumer<String> onText) {
        this.json = json;
        this.onText = onText;
    }

    public AssistantTurn read(InputStream input) throws IOException {
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
        LOG.fine("SSE assembled; chunks=" + chunks + "; text chars=" + content.length()
                + "; tool calls=" + completedCalls.size() + "; finish=" + finishReason);
        return new AssistantTurn(content.toString(), List.copyOf(completedCalls), finishReason);
    }

    private void dispatch(StringBuilder event) throws IOException {
        if (event.isEmpty()) return;
        if ("[DONE]".contentEquals(event)) { done = true; return; }
        ChatChunk chunk = json.readValue(event.toString(), ChatChunk.class);
        chunks++;
        if (chunk.choices() == null) return;
        for (ChunkChoice choice : chunk.choices()) {
            if (choice.finishReason() != null) finishReason = choice.finishReason();
            ChunkDelta delta = choice.delta();
            if (delta == null) continue;
            if (delta.content() != null && !delta.content().isEmpty()) {
                content.append(delta.content());
                LOG.fine("SSE chunk " + chunks + ": text chars=" + delta.content().length());
                onText.accept(delta.content());
            }
            if (delta.toolCalls() == null) continue;
            for (ToolCallDelta part : delta.toolCalls())
                calls.computeIfAbsent(part.index(), ignored -> new CallParts()).append(part);
            LOG.fine("SSE chunk " + chunks + ": tool fragments=" + delta.toolCalls().size());
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
