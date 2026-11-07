package dev.learning.agent.deepseek;

import dev.learning.agent.core.runtime.RunControl;
import dev.learning.agent.core.runtime.ExternalObserver;
import dev.learning.agent.core.runtime.TraceSpan;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ExternalObserver observer;

    StreamingHttp(ObjectMapper json, String key, String baseUrl, String path) {
        this(json, key, baseUrl, path, ExternalObserver.NOOP);
    }
    StreamingHttp(ObjectMapper json, String key, String baseUrl, String path, ExternalObserver observer) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("DeepSeek API key is required");
        if (baseUrl == null || !"https".equals(URI.create(baseUrl).getScheme())) {
            throw new IllegalArgumentException("DeepSeek base URL must use HTTPS");
        }
        this.json = json;
        this.key = key;
        this.observer = observer;
        endpoint = URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    InputStream send(Object body, RunControl control) throws IOException, InterruptedException {
        control.check();
        TraceSpan span = observer.http("POST", endpoint);
        try {
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
                span.attribute("http.response.status_code", response.statusCode());
                span.error("HTTP_" + response.statusCode());
                response.body().close();
                throw new IOException("DeepSeek returned HTTP " + response.statusCode());
            }
            InputStream input = response.body();
            span.attribute("http.response.status_code", response.statusCode());
            AutoCloseable cancellation = control.onCancel(() -> closeQuietly(input));
            var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
            var deadline = TIMER.schedule(() -> { timedOut.set(true); closeQuietly(input); },
                    Math.max(0, callNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            return new FilterInputStream(input) {
                public int read() throws IOException {
                    try { return super.read(); } catch (IOException error) { readFailed(error); throw error; }
                }
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    try { return super.read(bytes, offset, length); } catch (IOException error) { readFailed(error); throw error; }
                }
                private void readFailed(IOException error) {
                    if (control.cancelled()) span.attribute("agent_loop.cancelled", true);
                    else if (timedOut.get()) span.error("MODEL_HTTP_TIMEOUT");
                    else span.fail(error);
                }
                public void close() throws IOException {
                    deadline.cancel(false);
                    unregister(cancellation);
                    try { super.close(); }
                    finally {
                        if (timedOut.get()) span.error("MODEL_HTTP_TIMEOUT");
                        if (control.cancelled()) span.attribute("agent_loop.cancelled", true);
                        span.close();
                    }
                }
            };
        } catch (IOException | InterruptedException | RuntimeException error) {
            span.fail(error); span.close(); throw error;
        }
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
        int bytes = 0;
        boolean highSurrogate = false;
        while ((value = reader.read()) != -1 && value != '\n') {
            bytes += value < 0x80 ? 1 : value < 0x800 ? 2 : Character.isLowSurrogate((char) value) && highSurrogate ? 1 : 3;
            highSurrogate = Character.isHighSurrogate((char) value);
            if (bytes > 131_072) throw new IOException("SSE line is too large");
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
                    if (data.toString().getBytes(StandardCharsets.UTF_8).length > 131_072) throw new IOException("SSE event is too large");
                }
            }
            if (!data.isEmpty()) consumer.accept(data.toString());
        }
    }

    @FunctionalInterface interface EventConsumer { void accept(String data) throws IOException; }
}
