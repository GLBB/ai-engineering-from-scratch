package dev.learning.agent.app.chat;

import dev.learning.agent.app.settings.SettingsStore;
import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.AgentRunner;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.planning.ConversationMemory;
import dev.learning.agent.core.planning.TaskInput;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.TelemetryFactory;
import dev.learning.agent.core.planexecute.PlanExecuteRunner;
import dev.learning.agent.core.react.DefaultReActEngine;
import dev.learning.agent.core.react.ReActRunner;
import dev.learning.agent.core.rewoo.RewooRunner;
import dev.learning.agent.deepseek.AnthropicClient;
import dev.learning.agent.deepseek.ChatCompletionsClient;
import dev.learning.agent.deepseek.ResponsesClient;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.tool.BashTool;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.SearchTool;
import dev.learning.agent.tool.ToolRegistry;
import dev.learning.agent.tool.weather.CurrentWeatherTool;
import dev.learning.agent.tool.weather.WeatherForecastTool;
import dev.learning.agent.tool.weather.WeatherService;
import dev.learning.agent.tool.weather.OpenMeteoClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A conversation shares context across modes and permits at most one active request. */
@Service
final class AgentSessions {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final WeatherService weather;
    private final ChatModel client;
    private final Path projectRoot;
    private final String exaKey;
    private final TelemetryFactory telemetry;

    @org.springframework.beans.factory.annotation.Autowired
    AgentSessions(SettingsStore settings, TelemetryFactory telemetry) {
        this.telemetry = telemetry;
        weather = new WeatherService(new OpenMeteoClient(json, telemetry.external()), java.time.Clock.systemUTC(), telemetry.external());
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is required");
        String baseUrl = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        client = switch (settings.activeProtocol()) {
            case CHAT_COMPLETIONS -> new ChatCompletionsClient(json, key, baseUrl, telemetry.external());
            case RESPONSES -> new ResponsesClient(json, key, baseUrl, telemetry.external());
            case ANTHROPIC -> new AnthropicClient(json, key, baseUrl, telemetry.external());
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
        this(client, projectRoot, exaKey,
                new WeatherService(new OpenMeteoClient(new ObjectMapper()), java.time.Clock.systemUTC()));
    }
    AgentSessions(ChatModel client, Path projectRoot, String exaKey, WeatherService weather) {
        this(client, projectRoot, exaKey, weather, TelemetryFactory.NOOP);
    }
    AgentSessions(ChatModel client, Path projectRoot, String exaKey, WeatherService weather, TelemetryFactory telemetry) {
        this.client = client; this.projectRoot = projectRoot; this.exaKey = exaKey;
        this.weather = weather;
        this.telemetry = telemetry;
    }

    final class Session {
        private final String id = UUID.randomUUID().toString();
        private final String telemetrySessionId = UUID.randomUUID().toString();
        private final ToolRegistry tools = new ToolRegistry();
        private final ConversationMemory memory = new ConversationMemory();
        private AgentMode lastMode;
        private AgentRunner runner;
        private RunLease activeRun;
        private boolean contentRestricted;

        private Session() {
            tools.register(new CalculatorTool(json));
            tools.register(new BashTool(json, projectRoot, command -> false, output -> false));
            tools.register(new SearchTool(json, exaKey));
            tools.register(new CurrentWeatherTool(weather));
            tools.register(new WeatherForecastTool(weather));
        }
        String id() { return id; }
        synchronized RunLease accept(String prompt, ModelSettings settings, AgentMode requested) {
            if (prompt == null || prompt.isBlank() || prompt.length() > 10_000 || settings == null)
                throw new ApiFailure(400, "INVALID_REQUEST", "A nonblank prompt of at most 10000 characters is required.");
            if (activeRun != null) throw new ApiFailure(409, "RUN_IN_PROGRESS", "This conversation is already running.");
            AgentMode selected = requested != null ? requested : lastMode != null ? lastMode : AgentMode.REACT;
            AgentRunner candidate = runner != null && lastMode == selected ? runner : createRunner(selected);
            var request = new ExecutionRequest(UUID.randomUUID(), prompt, settings);
            var trace = telemetry.open(telemetrySessionId, request, selected, client.protocol());
            if (contentRestricted) trace.restrictContent();
            RunLease lease = new RunLease(request, selected, remembering(candidate), client, tools, this::release, trace);
            lastMode = selected; runner = candidate; activeRun = lease;
            lease.arm();
            return lease;
        }
        RunResult execute(RunLease lease, EventSink events) throws Exception {
            synchronized (this) { if (activeRun != lease) return null; }
            return lease.execute(events);
        }
        synchronized SessionStatus status() {
            return new SessionStatus(id, java.util.List.of(AgentMode.values()), AgentMode.REACT, lastMode,
                    activeRun != null, activeRun == null ? null : activeRun.requestId());
        }
        private AgentRunner createRunner(AgentMode selected) {
            var history = memory.history();
            return switch (selected) {
                case REACT -> new ReActRunner(new DefaultReActEngine(tools), history);
                case REWOO -> new RewooRunner(tools, history);
                case PLAN_EXECUTE -> new PlanExecuteRunner(tools, new DefaultReActEngine(tools), history);
            };
        }
        private AgentRunner remembering(AgentRunner selected) {
            return (request, context) -> {
                RunResult result;
                try {
                    result = selected.run(request, context);
                } catch (java.io.IOException | InterruptedException | RuntimeException error) {
                    ExecutionStatus status = error instanceof InterruptedException || context.control().cancelled()
                            ? ExecutionStatus.CANCELLED : ExecutionStatus.FAILED;
                    remember(request.prompt(), "", status, context);
                    throw error;
                }
                remember(request.prompt(), result.answer(), result.status(), context);
                return result;
            };
        }
        private synchronized void remember(String question, String answer, ExecutionStatus status, RunContext context) {
            contentRestricted |= context.telemetry().contentRestricted();
            var operations = context.evidence().snapshot().stream()
                    .map(e -> new TaskInput.ToolOutcome(e.tool(), e.result().status(), e.result().executed())).toList();
            memory.commit(new TaskInput.ConversationTurn(question, answer, status, operations));
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
                         AgentMode mode, boolean running, UUID activeRequestId) {}
}
