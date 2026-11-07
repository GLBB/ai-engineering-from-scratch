package dev.learning.agent.deepseek;

import dev.learning.agent.core.runtime.RunControl;
import dev.learning.agent.core.runtime.ExternalObserver;
import dev.learning.agent.model.ModelStreamObserver;
import dev.learning.agent.deepseek.model.ProviderUsage;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatMessage;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelRequest;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.model.ModelUse;
import dev.learning.agent.model.OutputMode;
import dev.learning.agent.model.ToolCall;
import dev.learning.agent.model.ToolSpec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Adapts the agent's conversation to DeepSeek's stateless Responses API. */
public final class ResponsesClient implements ChatModel {
    private final ObjectMapper json;
    private final StreamingHttp http;

    public ResponsesClient(ObjectMapper json, String key, String baseUrl) {
        this(json, key, baseUrl, ExternalObserver.NOOP);
    }
    public ResponsesClient(ObjectMapper json, String key, String baseUrl, ExternalObserver observer) {
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.http = new StreamingHttp(this.json, key, baseUrl, "/responses", observer);
    }

    @Override
    public String protocol() { return "Responses"; }

    @Override
    public AssistantTurn stream(ModelRequest modelRequest, Consumer<String> onText, RunControl control)
            throws IOException, InterruptedException {
        return stream(modelRequest, onText, control, ModelStreamObserver.NOOP);
    }
    public AssistantTurn stream(ModelRequest modelRequest, Consumer<String> onText, RunControl control, ModelStreamObserver observer)
            throws IOException, InterruptedException {
        try (InputStream input = http.send(request(modelRequest), control)) {
            return read(input, onText, observer);
        }
    }

    Request request(List<ChatMessage> messages, List<ToolSpec> tools, ModelSettings settings) {
        return request(new ModelRequest(messages, settings, tools, OutputMode.TEXT, 4096, ModelUse.EXECUTOR));
    }

    Request request(ModelRequest modelRequest) {
        List<ChatMessage> messages = modelRequest.messages();
        List<ToolSpec> tools = modelRequest.tools();
        ModelSettings settings = modelRequest.settings();
        List<Item> items = new ArrayList<>();
        for (ChatMessage message : messages) {
            if ("tool".equals(message.role())) {
                items.add(new Item("function_call_output", null, null, null, message.toolCallId(),
                        null, message.content(), null));
                continue;
            }
            if ("assistant".equals(message.role()) && message.reasoningContent() != null) {
                items.add(new Item("reasoning", null,
                        List.of(new Content("reasoning_text", message.reasoningContent())), null,
                        null, null, null, null));
            }
            if (message.content() != null && !message.content().isBlank()) {
                String contentType = "assistant".equals(message.role()) ? "output_text" : "input_text";
                items.add(new Item("message", message.role(), List.of(new Content(contentType, message.content())),
                        null, null, null, null, null));
            }
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    items.add(new Item("function_call", null, null, call.function().name(), call.id(),
                            call.function().arguments(), null, null));
                }
            }
        }
        List<Function> functions = tools.stream().map(tool -> new Function("function",
                tool.function().name(), tool.function().description(), tool.function().parameters())).toList();
        String effort = settings.thinking() ? settings.reasoningLevel() : "none";
        return new Request(settings.model(), items, new Reasoning(effort), true, functions.isEmpty() ? null : functions,
                functions.isEmpty() ? null : "auto", modelRequest.maxOutputTokens());
    }

    AssistantTurn read(InputStream input, Consumer<String> onText) throws IOException {
        return read(input, onText, ModelStreamObserver.NOOP);
    }
    AssistantTurn read(InputStream input, Consumer<String> onText, ModelStreamObserver observer) throws IOException {
        Response[] completed = new Response[1];
        int[] streamedCharacters = {0};
        StreamingHttp.readEvents(input, data -> {
            EventType event = json.readValue(data, EventType.class);
            if (event.type() != null && event.type().endsWith(".delta")) {
                Delta delta = json.readValue(data, Delta.class);
                if (delta.delta() != null) {
                    streamedCharacters[0] += delta.delta().length();
                    if (!delta.delta().isEmpty() && List.of("response.output_text.delta", "response.reasoning_text.delta",
                            "response.function_call_arguments.delta").contains(event.type()))
                        observer.chunk("response.output_text.delta".equals(event.type()));
                }
                if (streamedCharacters[0] > 16_000) throw new IOException("model output too large");
            }
            if ("response.output_text.delta".equals(event.type())) {
                Delta delta = json.readValue(data, Delta.class);
                if (delta.delta() != null) onText.accept(delta.delta());
            } else if ("response.completed".equals(event.type())) {
                completed[0] = json.readValue(data, Completed.class).response();
                if (completed[0] != null) observer.usage(ProviderUsage.toTokenUsage(completed[0].usage()), true);
            } else if ("response.failed".equals(event.type()) || "response.incomplete".equals(event.type())) {
                throw new IOException("DeepSeek Responses stream ended with " + event.type());
            } else if ("error".equals(event.type()) || event.type() == null) {
                throw new IOException("DeepSeek Responses stream returned an error");
            }
        });
        if (completed[0] == null || completed[0].output() == null) {
            throw new IOException("DeepSeek Responses stream ended without response.completed");
        }
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (Output item : completed[0].output()) {
            if ("function_call".equals(item.type())) {
                if (item.callId() == null || item.name() == null || item.arguments() == null) {
                    throw new IOException("incomplete Responses function call");
                }
                calls.add(new ToolCall(item.callId(), "function",
                        new ToolCall.FunctionCall(item.name(), item.arguments())));
            }
            if (item.content() == null) continue;
            for (Content part : item.content()) {
                if ("output_text".equals(part.type()) && part.text() != null) text.append(part.text());
                if ("reasoning_text".equals(part.type()) && part.text() != null) reasoning.append(part.text());
            }
        }
        return new AssistantTurn(text.toString(), List.copyOf(calls), calls.isEmpty() ? "stop" : "tool_calls",
                reasoning.isEmpty() ? null : reasoning.toString(), null, ProviderUsage.toTokenUsage(completed[0].usage()), new AssistantTurn.ResponseMetadata(completed[0].id(), completed[0].model()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Item(String type, String role, List<Content> content, String name, @JsonProperty("call_id") String callId,
                String arguments, String output, String id) {}
    record Content(String type, String text) {}
    record Function(String type, String name, String description, ToolSpec.ParameterSchema parameters) {}
    record Reasoning(String effort) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String model, List<Item> input, Reasoning reasoning, boolean stream,
                   List<Function> tools, @JsonProperty("tool_choice") String toolChoice, @JsonProperty("max_output_tokens") int maxOutputTokens) {}
    record EventType(String type) {}
    record Delta(String type, String delta) {}
    record Completed(String type, Response response) {}
    record Response(List<Output> output, ProviderUsage usage, String id, String model) {}
    record Output(String type, List<Content> content, @JsonProperty("call_id") String callId,
                  String name, String arguments) {}
}
