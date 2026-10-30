package dev.learning.agent.deepseek;

import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatMessage;
import dev.learning.agent.deepseek.model.ChatRequest;
import dev.learning.agent.deepseek.model.ToolSpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** HTTP boundary for DeepSeek's streaming Chat Completions API. */
public final class DeepSeekClient {
    private static final Logger LOG = Logger.getLogger(DeepSeekClient.class.getName());
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final URI endpoint;

    public DeepSeekClient(ObjectMapper json, String key, String model, String baseUrl) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("DEEPSEEK_API_KEY is required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("base URL is required");
        URI base = URI.create(baseUrl);
        if (!"https".equals(base.getScheme())) throw new IllegalArgumentException("DeepSeek base URL must use HTTPS");
        this.json = json;
        this.key = key;
        this.model = model;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
    }

    public AssistantTurn stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onText)
            throws IOException, InterruptedException {
        ChatRequest body = ChatRequest.forAgent(model, messages, tools);
        LOG.fine("DeepSeek request prepared; model=" + model + "; messages=" + messages.size()
                + "; tools=" + tools.size() + "; stream=true; thinking=disabled");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        long start = System.nanoTime();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        LOG.info("DeepSeek HTTP status=" + response.statusCode() + "; headers in "
                + (System.nanoTime() - start) / 1_000_000 + " ms");
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("DeepSeek returned HTTP " + response.statusCode());
        }
        AssistantTurn turn = new StreamAssembler(json, onText).read(response.body());
        LOG.info("DeepSeek stream complete in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        return turn;
    }
}
