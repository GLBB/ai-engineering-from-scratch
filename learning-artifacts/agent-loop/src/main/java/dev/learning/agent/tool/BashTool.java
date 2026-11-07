package dev.learning.agent.tool;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.core.runtime.TraceSpan;
import dev.learning.agent.model.ToolSpec.FunctionSpec;
import dev.learning.agent.model.ToolSpec.ParameterSchema;
import dev.learning.agent.model.ToolSpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Executes only after a human approves the exact command. */
public final class BashTool implements AgentTool {
    private static final Logger LOG = LoggerFactory.getLogger(BashTool.class);
    private static final int MAX_OUTPUT = 12_000;
    private final ObjectMapper json;
    private final Path workdir;
    private final Predicate<String> approve;
    private final Predicate<String> approveOutput;

    public BashTool(ObjectMapper json, Path workdir, Predicate<String> approve, Predicate<String> approveOutput) {
        this.json = json;
        this.workdir = workdir;
        this.approve = approve;
        this.approveOutput = approveOutput;
    }

    public ToolInput decode(String arguments) throws java.io.IOException {
        return new BashInput(ToolInputDecoder.oneString(arguments, "command", 2000));
    }

    public ToolSpec definition() {
        return new ToolSpec("function", new FunctionSpec("bash",
                "Run a Bash command in the project directory after user approval. Read-only commands are preferred.",
                ParameterSchema.oneString("command", "The Bash command to run.")));
    }

    public ToolResult execute(ToolInput input, RunScope scope, RunContext context) throws Exception {
        if (!(input instanceof BashInput args)) throw new IllegalArgumentException("expected bash arguments");
        String command = args.command();
        if (command == null || command.isBlank() || command.length() > 2_000)
            throw new IllegalArgumentException("command is missing or too long");
        if (!(context == null ? approve.test(command) : context.approvals().approve("command", command, scope, context.control()))) {
            LOG.info("Bash command declined; chars={}", command.length());
            return new ToolResult(ToolStatus.DENIED, "user declined Bash command", "COMMAND_DENIED", false, false, false);
        }
        LOG.info("Bash command approved; chars={}", command.length());

        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", command)
                .directory(workdir.toFile()).redirectErrorStream(true);
        builder.environment().remove("DEEPSEEK_API_KEY");
        builder.environment().remove("EXA_AI_KEY");
        builder.environment().remove("EXA_API_KEY");
        builder.environment().remove("LANGFUSE_PUBLIC_KEY");
        builder.environment().remove("LANGFUSE_SECRET_KEY");
        builder.environment().remove("OTEL_EXPORTER_OTLP_HEADERS");
        builder.environment().remove("OTEL_EXPORTER_OTLP_TRACES_HEADERS");
        if (context != null) context.control().check();
        TraceSpan processSpan = context == null ? TraceSpan.NOOP : context.telemetry().external().operation("agent_loop.bash.process");
        CapturedOutput output = new CapturedOutput();
        Process process;
        boolean finished;
        long start = System.nanoTime();
        try {
            process = builder.start();
            processSpan.attribute("process.executable.name", "bash");
            AutoCloseable cancellation = context == null ? () -> {} : context.control().onCancel(() -> stop(process));
            Thread reader = new Thread(() -> output.drain(process), "bash-output-reader");
            reader.setDaemon(true);
            reader.start();
            try {
                long waitMs = context == null ? 15_000 : Math.min(15_000, Math.max(1, context.control().remaining().toMillis()));
                finished = process.waitFor(waitMs, TimeUnit.MILLISECONDS);
                if (!finished) stop(process);
                reader.join(2_000);
                if (context != null) context.control().check();
            } finally {
                cancellation.close();
                if (process.isAlive()) stop(process);
            }
            if (finished) {
                processSpan.attribute("process.exit.code", process.exitValue());
                if (process.exitValue() != 0) processSpan.error("BASH_EXIT_NONZERO");
            } else processSpan.error("BASH_TIMEOUT");
        } catch (Exception error) { processSpan.fail(error); throw error; }
        finally { processSpan.close(); }
        String result = "exit=" + (finished ? process.exitValue() : "timeout") + "\n" + output.text();
        LOG.info("Bash process finished; status={}; elapsed={} ms; output chars={}",
                finished ? process.exitValue() : "timeout", (System.nanoTime() - start) / 1_000_000,
                result.length());
        if (!(context == null ? approveOutput.test(result) : context.approvals().approve("output", result, scope, context.control()))) {
            LOG.info("Bash output sharing declined");
            return new ToolResult(ToolStatus.DENIED, "user declined sharing Bash output", "OUTPUT_DENIED", true, false, false);
        }
        LOG.info("Bash output sharing approved");
        ToolStatus status = !finished ? ToolStatus.TIMEOUT : process.exitValue() == 0 ? ToolStatus.SUCCESS : ToolStatus.ERROR;
        return new ToolResult(status, result, status == ToolStatus.SUCCESS ? null : "BASH_FAILED", true,
                status == ToolStatus.SUCCESS, output.truncated);
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        process.destroyForcibly();
    }

    record BashArgs(String command) {}

    private static final class CapturedOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean truncated;

        void drain(Process process) {
            try (var input = process.getInputStream()) {
                byte[] chunk = new byte[1024];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    synchronized (bytes) {
                        int keep = Math.min(count, MAX_OUTPUT - bytes.size());
                        if (keep > 0) bytes.write(chunk, 0, keep);
                        if (keep < count) truncated = true;
                    }
                }
            } catch (IOException ignored) { /* Process may be killed on timeout. */ }
        }

        String text() {
            synchronized (bytes) {
                return bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
                        + (truncated ? "\n[output truncated]" : "");
            }
        }
    }
}
