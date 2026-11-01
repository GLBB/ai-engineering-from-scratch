package dev.learning.agent.app.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.app.settings.SettingsStore;
import dev.learning.agent.core.*;
import dev.learning.agent.deepseek.ChatCompletionsClient;
import dev.learning.agent.deepseek.ResponsesClient;
import dev.learning.agent.deepseek.AnthropicClient;
import dev.learning.agent.core.ChatModel;
import dev.learning.agent.deepseek.model.ModelSettings;
import dev.learning.agent.tool.BashTool;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.SearchTool;
import dev.learning.agent.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Each conversation owns one fixed-mode runner and at most one active request. */
@Service
final class AgentSessions {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ChatModel client;
    private final Path projectRoot;
    private final String exaKey;

    @org.springframework.beans.factory.annotation.Autowired
    AgentSessions(SettingsStore settings) {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is required");
        String baseUrl = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        client = switch (settings.activeProtocol()) {
            case CHAT_COMPLETIONS -> new ChatCompletionsClient(json, key, baseUrl);
            case RESPONSES -> new ResponsesClient(json, key, baseUrl);
            case ANTHROPIC -> new AnthropicClient(json, key, baseUrl);
        };
        String configuredExaKey = System.getenv("EXA_AI_KEY");
        exaKey = configuredExaKey == null || configuredExaKey.isBlank()
                ? System.getenv("EXA_API_KEY") : configuredExaKey;
        Path current = Path.of("").toAbsolutePath().normalize();
        Path module = current.resolve("learning-artifacts/agent-loop");
        projectRoot = Files.isRegularFile(module.resolve("pom.xml")) ? module : current;
    }

    Session create() {
        Session session = new Session();
        sessions.put(session.id, session);
        return session;
    }

    Session get(String id) {
        return id == null ? null : sessions.get(id);
    }

    // Test constructor uses the exact same Session and runner factory without remote credentials.
    AgentSessions(ChatModel client, Path projectRoot, String exaKey) {
        this.client = client; this.projectRoot = projectRoot; this.exaKey = exaKey;
    }

    final class Session {
        private final String id = UUID.randomUUID().toString();
        private final ToolRegistry tools = new ToolRegistry();
        private AgentMode mode;
        private AgentRunner runner;
        private RunLease activeRun;

        private Session() {
            tools.register(new CalculatorTool(json));
            tools.register(new BashTool(json, projectRoot, command -> false, output -> false));
            tools.register(new SearchTool(json, exaKey));
        }
        String id() { return id; }
        synchronized RunLease accept(String prompt, ModelSettings settings, AgentMode requested) {
            if (prompt == null || prompt.isBlank() || prompt.length() > 10_000 || settings == null)
                throw new ApiFailure(400, "INVALID_REQUEST", "A nonblank prompt of at most 10000 characters is required.");
            if (activeRun != null) throw new ApiFailure(409, "RUN_IN_PROGRESS", "This conversation is already running.");
            AgentMode selected = requested != null ? requested : mode != null ? mode : AgentMode.REACT;
            if (mode != null && mode != selected) throw new ApiFailure(409, "MODE_LOCKED", "Start a new conversation to change mode.");
            AgentRunner candidate = runner != null ? runner : switch (selected) {
                case REACT -> new ReActRunner(new DefaultReActEngine(tools));
                case REWOO -> new dev.learning.agent.core.rewoo.RewooRunner(tools);
                case PLAN_EXECUTE -> new dev.learning.agent.core.planexecute.PlanExecuteRunner(tools, new DefaultReActEngine(tools));
            };
            var request = new ExecutionRequest(UUID.randomUUID(), prompt, settings);
            RunLease lease = new RunLease(request, selected, candidate, client, tools, this::release);
            mode = selected; runner = candidate; activeRun = lease;
            lease.arm();
            return lease;
        }
        RunResult execute(RunLease lease, EventSink events) throws Exception {
            synchronized (this) { if (activeRun != lease) return null; }
            return lease.execute(events);
        }
        synchronized SessionStatus status() {
            return new SessionStatus(id, java.util.List.of(AgentMode.values()), AgentMode.REACT, mode, mode != null,
                    activeRun != null, activeRun == null ? null : activeRun.requestId());
        }
        boolean decide(String approvalId, boolean approved) {
            RunLease current;
            synchronized (this) { current = activeRun; }
            return current != null && current.decide(approvalId, approved);
        }
        private synchronized void release(RunLease expected) {
            if (activeRun == expected) activeRun = null;
        }
    }
    record SessionStatus(String sessionId, java.util.List<AgentMode> supportedModes, AgentMode defaultMode,
                         AgentMode mode, boolean modeLocked, boolean running, UUID activeRequestId) {}
}
