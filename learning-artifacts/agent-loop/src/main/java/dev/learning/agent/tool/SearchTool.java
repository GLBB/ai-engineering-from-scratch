package dev.learning.agent.tool;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.core.runtime.TraceSpan;
import dev.learning.agent.model.ToolSpec.FunctionSpec;
import dev.learning.agent.model.ToolSpec.ParameterSchema;
import dev.learning.agent.model.ToolSpec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Exa search adapter with credentials supplied by application configuration. */
public final class SearchTool implements AgentTool {
    private static final Logger LOG = LoggerFactory.getLogger(SearchTool.class);
    private final HttpClient http =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json;
    private final String key;

    public SearchTool(ObjectMapper json, String key) {
        this.json = json;
        this.key = key;
    }

    public ToolInput decode(String arguments) throws java.io.IOException {
        return new SearchInput(ToolInputDecoder.oneString(arguments, "query", 500));
    }

    public ToolSpec definition() {
        return new ToolSpec("function",
            new FunctionSpec("search", "Search the public web using Exa and return titles, URLs, and short excerpts.",
                ParameterSchema.oneString("query", "Search query in the user's language.")));
    }

    public ToolResult execute(ToolInput input, RunScope scope, RunContext context) throws Exception {
        if (!(input instanceof SearchInput args)) {
            throw new IllegalArgumentException("expected search arguments");
        }
        if (args.query() == null || args.query().isBlank() || args.query().length() > 500) {
            throw new IllegalArgumentException("query is missing or too long");
        }
        if (key == null || key.isBlank()) {
            return new ToolResult(ToolStatus.ERROR, "search unavailable; set EXA_AI_KEY", "SEARCH_UNAVAILABLE", false,
                false, false);
        }
        if (context != null) {
            context.control().check();
        }
        LOG.info("Exa search started; query chars={}; limit=5", args.query().length());
        SearchRequest body = new SearchRequest(args.query(), "auto", 5, new Contents(true));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.exa.ai/search"))
            .timeout(Duration.ofSeconds(30)).header("x-api-key", key).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        long start = System.nanoTime();
        if (context != null) {
            context.telemetry().toolHttpRequests(1);
        }
        try (TraceSpan span =
            context == null ? TraceSpan.NOOP : context.telemetry().external().http("POST", request.uri())) {
            var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            AutoCloseable cancellation = context == null ? () -> {
            } : context.control().onCancel(() -> future.cancel(true));
            HttpResponse<String> response;
            try {
                long timeout =
                    context == null ? 30_000 : Math.min(30_000, Math.max(1, context.control().remaining().toMillis()));
                response = future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                span.fail(e);
                future.cancel(true);
                throw e;
            } finally {
                cancellation.close();
            }
            span.attribute("http.response.status_code", response.statusCode());
            if (response.statusCode() >= 400) {
                span.error("HTTP_" + response.statusCode());
            }
            LOG.info("Exa HTTP status={}; elapsed={} ms", response.statusCode(),
                (System.nanoTime() - start) / 1_000_000);
            return response(response);
        }
    }

    private ToolResult response(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("Exa returned HTTP " + response.statusCode());
        }
        SearchResponse found = json.readValue(response.body(), SearchResponse.class);
        LOG.info("Exa results={}", found.results() == null ? 0 : found.results().size());
        if (found.results() == null || found.results().isEmpty()) {
            return new ToolResult(ToolStatus.SUCCESS, "No results found.", null, true, false, false);
        }
        StringBuilder out = new StringBuilder();
        for (SearchResult result : found.results()) {
            if (out.length() > 5_000) {
                out.append("\n[results truncated]");
                break;
            }
            out.append(result.title() == null ? "Untitled" : result.title()).append("\n").append(result.url())
                .append("\n");
            if (result.highlights() != null && !result.highlights().isEmpty()) {
                out.append(result.highlights().get(0), 0, Math.min(result.highlights().get(0).length(), 500))
                    .append("\n");
            }
            out.append("\n");
        }
        return new ToolResult(ToolStatus.SUCCESS, out.toString(), null, true, true,
            out.indexOf("[results truncated]") >= 0);
    }

    record SearchArgs(String query) {
    }

    record SearchRequest(String query, String type, int numResults, Contents contents) {
    }

    record Contents(boolean highlights) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResult(String title, String url, List<String> highlights) {
    }
}
