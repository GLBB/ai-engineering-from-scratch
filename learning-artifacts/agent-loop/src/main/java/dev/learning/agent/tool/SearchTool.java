package dev.learning.agent.tool;

import dev.learning.agent.deepseek.model.ToolSpec;
import dev.learning.agent.deepseek.model.ToolSpec.FunctionSpec;
import dev.learning.agent.deepseek.model.ToolSpec.ParameterSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/** Exa search adapter. Main supplies EXA_AI_KEY, with EXA_API_KEY as fallback. */
public final class SearchTool implements AgentTool {
    private static final Logger LOG = Logger.getLogger(SearchTool.class.getName());
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json;
    private final String key;

    public SearchTool(ObjectMapper json, String key) {
        this.json = json;
        this.key = key;
    }

    public ToolSpec definition() {
        return new ToolSpec("function", new FunctionSpec("search",
                "Search the public web using Exa and return titles, URLs, and short excerpts.",
                ParameterSchema.oneString("query", "Search query in the user's language.")));
    }

    public String execute(String arguments) throws Exception {
        SearchArgs args = json.readValue(arguments, SearchArgs.class);
        if (args.query() == null || args.query().isBlank() || args.query().length() > 500)
            return "error: query is missing or too long";
        if (key == null || key.isBlank()) return "error: search unavailable; set EXA_AI_KEY";
        LOG.info("Exa search started; query chars=" + args.query().length() + "; limit=5");
        SearchRequest body = new SearchRequest(args.query(), "auto", 5, new Contents(true));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.exa.ai/search"))
                .timeout(Duration.ofSeconds(30))
                .header("x-api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        LOG.info("Exa HTTP status=" + response.statusCode() + "; elapsed="
                + (System.nanoTime() - start) / 1_000_000 + " ms");
        if (response.statusCode() != 200) throw new IOException("Exa returned HTTP " + response.statusCode());
        SearchResponse found = json.readValue(response.body(), SearchResponse.class);
        LOG.info("Exa results=" + (found.results() == null ? 0 : found.results().size()));
        if (found.results() == null || found.results().isEmpty()) return "No results found.";
        StringBuilder out = new StringBuilder();
        for (SearchResult result : found.results()) {
            if (out.length() > 5_000) { out.append("\n[results truncated]"); break; }
            out.append(result.title() == null ? "Untitled" : result.title())
                    .append("\n").append(result.url()).append("\n");
            if (result.highlights() != null && !result.highlights().isEmpty())
                out.append(result.highlights().get(0), 0,
                        Math.min(result.highlights().get(0).length(), 500)).append("\n");
            out.append("\n");
        }
        return out.toString();
    }

    record SearchArgs(String query) {}
    record SearchRequest(String query, String type, int numResults, Contents contents) {}
    record Contents(boolean highlights) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResult(String title, String url, List<String> highlights) {}
}
