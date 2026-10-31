package dev.learning.agent.deepseek.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions 请求体。字段以官方文档为准；未设置的可选字段不会发出。
 * 本 demo 通过 {@link #forAgent} 启用流式输出、可选思考模式和工具调用。
 * 文档：https://api-docs.deepseek.com/api/create-chat-completion/
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatRequest {
    /** 模型标识，例如 deepseek-flash。必填。 */
    @JsonProperty("model") private final String model;
    /** 包含 system、user、assistant、tool 消息的上下文。必填。 */
    @JsonProperty("messages") private final List<ChatMessage> messages;
    /** 思考模式开关；不传时 DeepSeek 默认启用思考。 */
    @JsonProperty("thinking") private Thinking thinking;
    /** 思考强度：none、low、high、max；thinking.type 优先级更高。 */
    @JsonProperty("reasoning_effort") private String reasoningEffort;
    /** 最多生成的 token 数。 */
    @JsonProperty("max_tokens") private Integer maxTokens;
    /** 输出格式，text 或 json_object。 */
    @JsonProperty("response_format") private ResponseFormat responseFormat;
    /** 停止序列；API 也接受单个字符串，本实体采用字符串列表形式。 */
    @JsonProperty("stop") private List<String> stop;
    /** 是否以 SSE 逐片返回。 */
    @JsonProperty("stream") private Boolean stream;
    /** 流式响应选项，例如是否显式包含 usage。 */
    @JsonProperty("stream_options") private StreamOptions streamOptions;
    /** 采样温度，0 到 2；思考模式下无效。 */
    @JsonProperty("temperature") private Double temperature;
    /** 核采样阈值，0 到 1；通常与 temperature 二选一调整。 */
    @JsonProperty("top_p") private Double topP;
    /** 可由模型调用的函数工具定义。 */
    @JsonProperty("tools") private List<ToolSpec> tools;
    /** 工具选择策略：auto、none、required；此实体使用字符串形式。 */
    @JsonProperty("tool_choice") private String toolChoice;
    /** 是否返回对数概率。 */
    @JsonProperty("logprobs") private Boolean logprobs;
    /** 返回每个位置概率最高的候选数，需同时启用 logprobs。 */
    @JsonProperty("top_logprobs") private Integer topLogprobs;
    /** 调用方自定义用户标识，不要传个人隐私信息。 */
    @JsonProperty("user_id") private String userId;
    /** 已弃用：DeepSeek 当前不再支持该频率惩罚参数。 */
    @Deprecated @JsonProperty("frequency_penalty") private Double frequencyPenalty;
    /** 已弃用：DeepSeek 当前不再支持该存在惩罚参数。 */
    @Deprecated @JsonProperty("presence_penalty") private Double presencePenalty;

    public ChatRequest(String model, List<ChatMessage> messages) {
        this.model = model;
        this.messages = List.copyOf(messages);
    }

    public static ChatRequest forAgent(ModelSettings settings, List<ChatMessage> messages,
                                       List<ToolSpec> tools) {
        ChatRequest request = new ChatRequest(settings.model(), messages);
        request.thinking = new Thinking(settings.thinking() ? "enabled" : "disabled");
        request.reasoningEffort = settings.apiReasoningEffort();
        request.stream = true;
        request.tools = List.copyOf(tools);
        request.toolChoice = "auto";
        return request;
    }

    /** type 为 enabled 或 disabled，控制是否生成推理内容。 */
    public record Thinking(String type) {}
    /** type 为 text 或 json_object；JSON 模式需要在提示词中要求输出 JSON。 */
    public record ResponseFormat(String type) {}
    /** include_usage 为 true 时，在最后一个 SSE 片段中附带 token 用量。 */
    public record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {}
}
