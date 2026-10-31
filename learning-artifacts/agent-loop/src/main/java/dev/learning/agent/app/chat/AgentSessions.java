package dev.learning.agent.app.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.app.settings.SettingsStore;
import dev.learning.agent.core.AgentLoop;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps one in-memory AgentLoop and approval state per browser session. */
@Service
final class AgentSessions {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ChatModel client;
    private final Path projectRoot;
    private final String exaKey;

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

    final class Session {
        private final String id = UUID.randomUUID().toString();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<EventSink> currentSink = new AtomicReference<>();
        private final BrowserApproval approvals = new BrowserApproval(this::emit);
        private final AgentLoop loop;

        private Session() {
            ToolRegistry tools = new ToolRegistry();
            tools.register(new CalculatorTool(json));
            tools.register(new BashTool(json, projectRoot, approvals::approveCommand, approvals::approveOutput));
            tools.register(new SearchTool(json, exaKey));
            loop = new AgentLoop(client, tools, 8,
                    delta -> emit("text", delta),
                    event -> emit("tool", event),
                    event -> emit("trace", event));
        }

        String id() {
            return id;
        }

        boolean begin() {
            return running.compareAndSet(false, true);
        }

        void run(String prompt, ModelSettings settings, EventSink sink) throws Exception {
            currentSink.set(sink);
            try {
                loop.run(prompt, settings);
            } finally {
                approvals.cancel();
                currentSink.set(null);
                running.set(false);
            }
        }

        boolean decide(String id, boolean approved) {
            return approvals.decide(id, approved);
        }

        private void emit(String type, Object data) {
            EventSink sink = currentSink.get();
            if (sink != null) {
                sink.emit(type, data);
            }
        }
    }
}
