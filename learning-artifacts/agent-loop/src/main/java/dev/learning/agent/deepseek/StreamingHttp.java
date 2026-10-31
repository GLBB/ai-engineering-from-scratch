package dev.learning.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends one streaming request and reads complete SSE data frames. */
final class StreamingHttp {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json;
    private final String key;
    private final URI endpoint;

    StreamingHttp(ObjectMapper json, String key, String baseUrl, String path) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("DEEPSEEK_API_KEY is required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("base URL is required");
        URI base = URI.create(baseUrl);
        if (!"https".equals(base.getScheme())) throw new IllegalArgumentException("DeepSeek base URL must use HTTPS");
        this.json = json;
        this.key = key;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    InputStream send(Object body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("DeepSeek returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    static void readEvents(InputStream input, EventConsumer consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) consumer.accept(data.toString());
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            if (!data.isEmpty()) consumer.accept(data.toString());
        }
    }

    @FunctionalInterface
    interface EventConsumer {
        void accept(String data) throws IOException;
    }
}
