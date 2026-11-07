package dev.learning.agent.deepseek;

import dev.learning.agent.deepseek.model.ChatChunk.ChunkChoice;
import dev.learning.agent.deepseek.model.ChatChunk.ChunkDelta;
import dev.learning.agent.deepseek.model.ChatChunk.ToolCallDelta;
import dev.learning.agent.deepseek.model.ChatChunk;
import dev.learning.agent.deepseek.model.ProviderUsage;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.TokenUsage;
import dev.learning.agent.model.ModelStreamObserver;
import dev.learning.agent.model.ToolCall.FunctionCall;
import dev.learning.agent.model.ToolCall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public final class StreamAssembler {
    private static final Logger LOG = LoggerFactory.getLogger(StreamAssembler.class);
    private final ObjectMapper json;
    private final Consumer<String> onText;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoningContent = new StringBuilder();
    private final Map<Integer, CallParts> calls = new TreeMap<>();
    private String finishReason;
    private boolean done;
    private int chunks;
    private TokenUsage usage;
    private final ModelStreamObserver observer;
    private String responseId;
    private String responseModel;

    public StreamAssembler(ObjectMapper json, Consumer<String> onText) {
        this(json, onText, ModelStreamObserver.NOOP);
    }

    public StreamAssembler(ObjectMapper json, Consumer<String> onText, ModelStreamObserver observer) {
        this.json = json;
        this.onText = onText;
        this.observer = observer;
    }

    public AssistantTurn read(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder event = new StringBuilder();
            int total = 0;
            while ((line = StreamingHttp.readLine(reader)) != null) {
                if ((total += line.length()) > 1_048_576) {
                    throw new IOException("model stream is too large");
                }
                if (line.isEmpty()) {
                    dispatch(event);
                    event.setLength(0);
                    if (done) {
                        break;
                    }
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) {
                        event.append('\n');
                    }
                    event.append(line.substring(5).stripLeading());
                    if (event.toString().getBytes(StandardCharsets.UTF_8).length > 131_072) {
                        throw new IOException("SSE event is too large");
                    }
                }
            }
            if (!event.isEmpty()) {
                dispatch(event);
            }
        }
        if (!done) {
            throw new IOException("DeepSeek stream ended without [DONE]");
        }
        if (finishReason == null) {
            throw new IOException("DeepSeek stream has no finish reason");
        }
        List<ToolCall> completedCalls = new ArrayList<>();
        for (CallParts call : calls.values()) {
            completedCalls.add(call.build());
        }
        // DeepSeek may attach final usage to a choice rather than a separate empty-choices frame.
        if (usage != null) {
            observer.usage(usage, true);
        }
        LOG.debug("SSE assembled; chunks={}; text chars={}; reasoning chars={}; tool calls={}; finish={}", chunks,
            content.length(), reasoningContent.length(), completedCalls.size(), finishReason);
        return new AssistantTurn(content.toString(), List.copyOf(completedCalls), finishReason,
            reasoningContent.isEmpty() ? null : reasoningContent.toString(), null, usage,
            new AssistantTurn.ResponseMetadata(responseId, responseModel));
    }

    private void dispatch(StringBuilder event) throws IOException {
        if (event.isEmpty()) {
            return;
        }
        if ("[DONE]".contentEquals(event)) {
            done = true;
            return;
        }
        ChatChunk chunk = json.readValue(event.toString(), ChatChunk.class);
        if (chunk.id() != null) {
            responseId = chunk.id();
        }
        if (chunk.model() != null) {
            responseModel = chunk.model();
        }
        usage = TokenUsage.merge(usage, ProviderUsage.toTokenUsage(chunk.usage()));
        if (chunk.usage() != null) {
            observer.usage(usage, chunk.choices() != null && chunk.choices().isEmpty());
        }
        chunks++;
        if (chunk.choices() == null) {
            return;
        }
        for (ChunkChoice choice : chunk.choices()) {
            if (choice.finishReason() != null) {
                finishReason = choice.finishReason();
            }
            ChunkDelta delta = choice.delta();
            if (delta == null) {
                continue;
            }
            if (delta.content() != null && !delta.content().isEmpty()) {
                observer.chunk(true);
                content.append(delta.content());
                LOG.debug("SSE chunk {}: text chars={}", chunks, delta.content().length());
                onText.accept(delta.content());
            }
            if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                observer.chunk(false);
                reasoningContent.append(delta.reasoningContent());
                LOG.debug("SSE chunk {}: reasoning chars={}", chunks, delta.reasoningContent().length());
            }
            if (delta.toolCalls() == null) {
                continue;
            }
            for (ToolCallDelta part : delta.toolCalls()) {
                if (part.function() != null
                    && (part.function().arguments() != null && !part.function().arguments().isEmpty()
                        || part.function().name() != null && !part.function().name().isEmpty())) {
                    observer.chunk(false);
                }
                calls.computeIfAbsent(part.index(), ignored -> new CallParts()).append(part);
            }
            LOG.debug("SSE chunk {}: tool fragments={}", chunks, delta.toolCalls().size());
        }
        int length = content.length() + reasoningContent.length();
        for (CallParts call : calls.values()) {
            length += call.arguments.length() + call.name.length();
        }
        if (length > 16_000) {
            throw new IOException("model output too large");
        }
    }

    private static final class CallParts {
        private String id;
        private String type;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void append(ToolCallDelta part) {
            if (part.id() != null) {
                id = part.id();
            }
            if (part.type() != null) {
                type = part.type();
            }
            if (part.function() != null) {
                if (part.function().name() != null) {
                    name.append(part.function().name());
                }
                if (part.function().arguments() != null) {
                    arguments.append(part.function().arguments());
                }
            }
        }

        ToolCall build() throws IOException {
            if (id == null || name.isEmpty()) {
                throw new IOException("incomplete streamed tool call");
            }
            return new ToolCall(id, type == null ? "function" : type,
                new FunctionCall(name.toString(), arguments.toString()));
        }
    }
}
