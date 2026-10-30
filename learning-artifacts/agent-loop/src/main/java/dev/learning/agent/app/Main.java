package dev.learning.agent.app;

import dev.learning.agent.core.AgentLoop;
import dev.learning.agent.deepseek.DeepSeekClient;
import dev.learning.agent.tool.BashTool;
import dev.learning.agent.tool.CalculatorTool;
import dev.learning.agent.tool.SearchTool;
import dev.learning.agent.tool.ToolRegistry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Interactive CLI. Answers stream as DeepSeek emits text deltas. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        Path projectRoot = projectRoot();
        Path logDirectory = projectRoot.resolve("logs");
        Files.createDirectories(logDirectory);
        System.setProperty("AGENT_LOG_DIR", logDirectory.toString());
        Logger log = LoggerFactory.getLogger(Main.class);
        log.info("Agent starting; logs directory={}", logDirectory);
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is required");
        ObjectMapper json = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        BufferedReader terminal = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(json));
        tools.register(new BashTool(json, projectRoot,
                command -> approve(terminal, "Run Bash command", command),
                output -> approve(terminal, "Send Bash output to DeepSeek", output)));
        String exaKey = System.getenv("EXA_AI_KEY");
        if (exaKey == null || exaKey.isBlank()) exaKey = System.getenv("EXA_API_KEY");
        tools.register(new SearchTool(json, exaKey));
        log.info("Tools ready: calculator, bash, search; Exa credential={}",
                exaKey == null || exaKey.isBlank() ? "missing" : "configured");
        DeepSeekClient client = new DeepSeekClient(json, key,
                System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-flash"),
                System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        AtomicBoolean textStarted = new AtomicBoolean();
        AgentLoop agent = new AgentLoop(client, tools, 8, delta -> {
            if (textStarted.compareAndSet(false, true)) System.out.print("assistant> ");
            System.out.print(delta);
            System.out.flush();
        }, event -> System.err.println("[" + event + "]"));

        System.out.println("Agent ready. Enter a prompt, or /exit to quit.");
        while (true) {
            System.out.print("you> ");
            System.out.flush();
            String prompt = terminal.readLine();
            if (prompt == null || prompt.equalsIgnoreCase("/exit")) break;
            if (prompt.isBlank()) continue;
            textStarted.set(false);
            try {
                log.info("User turn accepted; characters={}", prompt.length());
                agent.run(prompt);
                System.out.println();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Agent interrupted.");
                break;
            } catch (Exception e) {
                log.warn("Agent turn failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                System.err.println("\nAgent error: " + e.getMessage());
            }
        }
        log.info("Agent stopped");
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path module = current.resolve("learning-artifacts/agent-loop");
        if (Files.isRegularFile(module.resolve("pom.xml"))) return module;
        return current;
    }

    private static boolean approve(BufferedReader terminal, String action, String content) {
        System.err.println("\n" + action + ":\n" + content);
        System.err.print("Approve? [y/N] ");
        System.err.flush();
        try { return "y".equalsIgnoreCase(terminal.readLine()); }
        catch (IOException e) { return false; }
    }
}
