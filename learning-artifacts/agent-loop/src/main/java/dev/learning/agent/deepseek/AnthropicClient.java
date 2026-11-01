package dev.learning.agent.deepseek;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.core.ChatModel;
import dev.learning.agent.deepseek.model.TokenUsage;
import dev.learning.agent.deepseek.model.ProviderUsage;
import dev.learning.agent.core.ModelRequest;
import dev.learning.agent.core.RunControl;
import dev.learning.agent.core.ModelUse;
import dev.learning.agent.core.OutputMode;
import dev.learning.agent.deepseek.model.AssistantBlock;
import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.deepseek.model.ToolCall;
import dev.learning.agent.deepseek.model.ToolSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Adapts conversations and streamed content blocks to Anthropic Messages. */
public final class AnthropicClient implements ChatModel {
    private final ObjectMapper json;
    private final StreamingHttp http;

    public AnthropicClient(ObjectMapper json, String key, String baseUrl) {
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.http = new StreamingHttp(this.json, key, baseUrl, "/anthropic/v1/messages");
    }

    @Override
    public String protocol() { return "Anthropic Messages"; }

    @Override
    public AssistantTurn stream(ModelRequest modelRequest, Consumer<String> onText, RunControl control)
            throws IOException, InterruptedException {
        try (InputStream input = http.send(request(modelRequest), control)) {
            return read(input, onText);
        }
    }

    Request request(List<ChatMessage> messages, List<ToolSpec> tools, ModelSettings settings) throws IOException {
        return request(new ModelRequest(messages, settings, tools, OutputMode.TEXT, 4096, ModelUse.EXECUTOR));
    }

    Request request(ModelRequest modelRequest) throws IOException {
        List<ChatMessage> messages = modelRequest.messages();
        List<ToolSpec> tools = modelRequest.tools();
        ModelSettings settings = modelRequest.settings();
        String system = null;
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if ("system".equals(message.role())) {
                system = message.content();
            } else if ("tool".equals(message.role())) {
                List<Block> toolResults = new ArrayList<>();
                do {
                    ChatMessage tool = messages.get(i);
                    toolResults.add(new Block("tool_result", null, null, null, null, null, null,
                            tool.toolCallId(), tool.content()));
                    i++;
                } while (i < messages.size() && "tool".equals(messages.get(i).role()));
                i--;
                result.add(new Message("user", toolResults));
            } else if ("assistant".equals(message.role())) {
                result.add(new Message("assistant", assistantBlocks(message)));
            } else {
                result.add(new Message(message.role(), List.of(textBlock(message.content()))));
            }
        }
        List<Function> functions = tools.stream().map(tool -> new Function(tool.function().name(),
                tool.function().description(), tool.function().parameters())).toList();
        return new Request(settings.model(), system, result, true,
                new Thinking(settings.thinking() ? "enabled" : "disabled"),
                settings.thinking() ? new OutputConfig(settings.reasoningLevel()) : null,
                functions.isEmpty() ? null : functions, modelRequest.maxOutputTokens());
    }

    private List<Block> assistantBlocks(ChatMessage message) throws IOException {
        List<Block> blocks = new ArrayList<>();
        if (message.blocks() != null) {
            for (AssistantBlock block : message.blocks()) {
                JsonNode input = block.input() == null ? null : json.readTree(block.input());
                blocks.add(new Block(block.type(), block.text(), block.thinking(), block.signature(),
                        block.id(), block.name(), input, null, null));
            }
            return blocks;
        }
        if (message.reasoningContent() != null) {
            blocks.add(new Block("thinking", null, message.reasoningContent(), null, null, null, null, null, null));
        }
        if (message.content() != null && !message.content().isBlank()) blocks.add(textBlock(message.content()));
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                blocks.add(new Block("tool_use", null, null, null, call.id(), call.function().name(),
                        json.readTree(call.function().arguments()), null, null));
            }
        }
        return blocks;
    }

    private Block textBlock(String text) {
        return new Block("text", text, null, null, null, null, null, null, null);
    }

    AssistantTurn read(InputStream input, Consumer<String> onText) throws IOException {
        Map<Integer, BlockBuilder> blocks = new TreeMap<>();
        String[] stopReason = new String[1];
        boolean[] done = new boolean[1];
        TokenUsage[] usage = new TokenUsage[1];
        StreamingHttp.readEvents(input, data -> {
            EventType event = json.readValue(data, EventType.class);
            if (event.type() == null || "error".equals(event.type())) {
                throw new IOException("DeepSeek Anthropic stream returned an error");
            }
            switch (event.type()) {
                case "message_start" -> {
                    MessageStart start = json.readValue(data, MessageStart.class);
                    if (start.message() != null) usage[0] = TokenUsage.from(start.message().usage());
                }
                case "content_block_start" -> {
                    BlockStart start = json.readValue(data, BlockStart.class);
                    if (start.contentBlock() == null || blocks.containsKey(start.index())) {
                        throw new IOException("invalid Anthropic content block");
                    }
                    blocks.put(start.index(), new BlockBuilder(start.contentBlock()));
                }
                case "content_block_delta" -> {
                    BlockDelta part = json.readValue(data, BlockDelta.class);
                    BlockBuilder block = blocks.get(part.index());
                    if (block == null || part.delta() == null) throw new IOException("missing Anthropic content block");
                    block.append(part.delta(), onText);
                }
                case "message_delta" -> {
                    MessageDelta part = json.readValue(data, MessageDelta.class);
                    usage[0] = TokenUsage.merge(usage[0], TokenUsage.from(part.usage()));
                    if (part.delta() != null && part.delta().stopReason() != null) stopReason[0] = part.delta().stopReason();
                }
                case "message_stop" -> done[0] = true;
                default -> { }
            }
        });
        if (!done[0] || stopReason[0] == null) throw new IOException("incomplete DeepSeek Anthropic stream");
        if (!"end_turn".equals(stopReason[0]) && !"tool_use".equals(stopReason[0])) {
            throw new IOException("DeepSeek Anthropic stopped unexpectedly: " + stopReason[0]);
        }
        List<AssistantBlock> saved = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (BlockBuilder builder : blocks.values()) {
            AssistantBlock block = builder.build();
            saved.add(block);
            if ("text".equals(block.type()) && block.text() != null) text.append(block.text());
            if ("thinking".equals(block.type()) && block.thinking() != null) reasoning.append(block.thinking());
            if ("tool_use".equals(block.type())) {
                if (block.id() == null || block.name() == null || block.input() == null) {
                    throw new IOException("incomplete Anthropic tool call");
                }
                calls.add(new ToolCall(block.id(), "function", new ToolCall.FunctionCall(block.name(), block.input())));
            }
        }
        if ("tool_use".equals(stopReason[0]) != !calls.isEmpty()) {
            throw new IOException("Anthropic stop reason does not match tool calls");
        }
        return new AssistantTurn(text.toString(), List.copyOf(calls), calls.isEmpty() ? "stop" : "tool_calls",
                reasoning.isEmpty() ? null : reasoning.toString(), List.copyOf(saved), usage[0]);
    }

    private final class BlockBuilder {
        private final Block initial;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private final StringBuilder input = new StringBuilder();

        BlockBuilder(Block initial) {
            this.initial = initial;
            if (initial.text() != null) text.append(initial.text());
            if (initial.thinking() != null) thinking.append(initial.thinking());
            if (initial.signature() != null) signature.append(initial.signature());
        }

        void append(Delta delta, Consumer<String> onText) {
            if (delta.text() != null) { text.append(delta.text()); onText.accept(delta.text()); }
            if (delta.thinking() != null) thinking.append(delta.thinking());
            if (delta.signature() != null) signature.append(delta.signature());
            if (delta.partialJson() != null) input.append(delta.partialJson());
        }

        AssistantBlock build() throws IOException {
            String toolInput = null;
            if ("tool_use".equals(initial.type())) {
                JsonNode parsed = input.isEmpty() ? initial.input() : json.readTree(input.toString());
                if (parsed == null || !parsed.isObject()) throw new IOException("invalid Anthropic tool input");
                toolInput = json.writeValueAsString(parsed);
            }
            return new AssistantBlock(initial.type(), "text".equals(initial.type()) ? text.toString() : null,
                    "thinking".equals(initial.type()) ? thinking.toString() : null,
                    signature.isEmpty() ? null : signature.toString(), initial.id(), initial.name(), toolInput);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Block(String type, String text, String thinking, String signature, String id, String name,
                 JsonNode input, @JsonProperty("tool_use_id") String toolUseId, String content) {}
    record Message(String role, List<Block> content) {}
    record Function(String name, String description, @JsonProperty("input_schema") ToolSpec.ParameterSchema inputSchema) {}
    record Thinking(String type) {}
    record OutputConfig(String effort) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String model, String system, List<Message> messages, boolean stream, Thinking thinking,
                   @JsonProperty("output_config") OutputConfig outputConfig, List<Function> tools,
                   @JsonProperty("max_tokens") int maxTokens) {}
    record EventType(String type) {}
    record BlockStart(int index, @JsonProperty("content_block") Block contentBlock) {}
    record BlockDelta(int index, Delta delta) {}
    record Delta(String type, String text, String thinking, String signature,
                 @JsonProperty("partial_json") String partialJson) {}
    record MessageStart(MessageInfo message) {}
    record MessageInfo(ProviderUsage usage) {}
    record MessageDelta(Stop delta, ProviderUsage usage) {}
    record Stop(@JsonProperty("stop_reason") String stopReason) {}
}
