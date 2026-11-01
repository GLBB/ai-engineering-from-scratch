package dev.learning.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.core.RunControl;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Cancellable HTTP and a deadline that also covers reading the response body. */
final class StreamingHttp {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agent-model-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json;
    private final String key;
    private final URI endpoint;

    StreamingHttp(ObjectMapper json, String key, String baseUrl, String path) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("DeepSeek API key is required");
        if (baseUrl == null || !"https".equals(URI.create(baseUrl).getScheme())) {
            throw new IllegalArgumentException("DeepSeek base URL must use HTTPS");
        }
        this.json = json;
        this.key = key;
        endpoint = URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    InputStream send(Object body, RunControl control) throws IOException, InterruptedException {
        control.check();
        long callNanos = Math.min(Duration.ofSeconds(120).toNanos(), control.remaining().toNanos());
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(callNanos))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        AutoCloseable requestCancellation = control.onCancel(() -> future.cancel(true));
        HttpResponse<InputStream> response;
        try {
            response = future.get(callNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("model HTTP request failed", e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("model request timed out", e);
        } catch (CancellationException e) {
            control.check();
            throw new IOException("model request cancelled", e);
        } finally {
            unregister(requestCancellation);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("DeepSeek returned HTTP " + response.statusCode());
        }
        InputStream input = response.body();
        AutoCloseable cancellation = control.onCancel(() -> closeQuietly(input));
        var deadline = TIMER.schedule(() -> closeQuietly(input),
                Math.max(0, callNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
        return new FilterInputStream(input) {
            public void close() throws IOException {
                deadline.cancel(false);
                unregister(cancellation);
                super.close();
            }
        };
    }

    private static void closeQuietly(InputStream input) {
        try { input.close(); } catch (IOException ignored) { /* Already closed. */ }
    }

    private static void unregister(AutoCloseable registration) {
        try { registration.close(); } catch (Exception ignored) { /* Cleanup is idempotent. */ }
    }

    static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1 && value != '\n') {
            if (line.length() >= 131_072) throw new IOException("SSE line is too large");
            if (value != '\r') line.append((char) value);
        }
        return value == -1 && line.isEmpty() ? null : line.toString();
    }

    static void readEvents(InputStream input, EventConsumer consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            int total = 0;
            while ((line = readLine(reader)) != null) {
                if ((total += line.length()) > 1_048_576) throw new IOException("model stream is too large");
                if (line.isEmpty()) {
                    if (!data.isEmpty()) consumer.accept(data.toString());
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                    if (data.length() > 131_072) throw new IOException("SSE event is too large");
                }
            }
            if (!data.isEmpty()) consumer.accept(data.toString());
        }
    }

    @FunctionalInterface interface EventConsumer { void accept(String data) throws IOException; }
}
