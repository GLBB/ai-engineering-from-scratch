package dev.learning.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Executes only after a human approves the exact command. */
final class BashTool implements AgentTool {
    private static final int MAX_OUTPUT = 12_000;
    private final ObjectMapper json;
    private final Path workdir;
    private final Predicate<String> approve;
    private final Predicate<String> approveOutput;

    BashTool(ObjectMapper json, Path workdir, Predicate<String> approve, Predicate<String> approveOutput) {
        this.json = json;
        this.workdir = workdir;
        this.approve = approve;
        this.approveOutput = approveOutput;
    }

    public ToolSpec definition() {
        return new ToolSpec("function", new FunctionSpec("bash",
                "Run a Bash command in the project directory after user approval. Read-only commands are preferred.",
                ParameterSchema.oneString("command", "The Bash command to run.")));
    }

    public String execute(String arguments) throws Exception {
        BashArgs args = json.readValue(arguments, BashArgs.class);
        String command = args.command();
        if (command == null || command.isBlank() || command.length() > 2_000)
            return "error: command is missing or too long";
        if (!approve.test(command)) return "error: user declined Bash command";

        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", command)
                .directory(workdir.toFile()).redirectErrorStream(true);
        builder.environment().remove("DEEPSEEK_API_KEY");
        builder.environment().remove("EXA_API_KEY");
        Process process = builder.start();
        CapturedOutput output = new CapturedOutput();
        Thread reader = new Thread(() -> output.drain(process), "bash-output-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        reader.join(2_000);
        String result = "exit=" + (finished ? process.exitValue() : "timeout") + "\n" + output.text();
        return approveOutput.test(result) ? result : "error: user declined sharing Bash output";
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
