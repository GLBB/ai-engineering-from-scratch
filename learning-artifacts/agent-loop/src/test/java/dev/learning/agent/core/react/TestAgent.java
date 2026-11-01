package dev.learning.agent.core.react;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.core.ExecutionStatus;
import dev.learning.agent.core.RunResult;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.core.runtime.RuntimeEvent;
import dev.learning.agent.core.runtime.TraceEvent;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelSettings;
import dev.learning.agent.tool.ToolRegistry;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

/** Keeps the old behavior tests readable while exercising the new production runner. */
final class TestAgent {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final ReActRunner runner;
    private final Consumer<String> text;
    private final Consumer<String> tool;
    private final Consumer<TraceEvent> trace;

    TestAgent(ChatModel model, ToolRegistry tools, int max, Consumer<String> text, Consumer<String> tool) {
        this(model, tools, max, text, tool, ignored -> {});
    }
    TestAgent(ChatModel model, ToolRegistry tools, int max, Consumer<String> text,
              Consumer<String> tool, Consumer<TraceEvent> trace) {
        this.model = model;
        this.tools = tools;
        this.runner = new ReActRunner(new DefaultReActEngine(tools), max);
        this.text = text;
        this.tool = tool;
        this.trace = trace;
    }
    void run(String prompt, ModelSettings settings) throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        RunContext context = RunContexts.create(id, AgentMode.REACT, model, tools, event -> {
            RuntimeEvent data = (RuntimeEvent) event;
            switch (data.type()) {
                case "text" -> text.accept((String) data.data());
                case "tool" -> tool.accept((String) data.data());
                case "trace" -> trace.accept((TraceEvent) data.data());
                default -> { }
            }
        }, (kind, content, scope, control) -> false);
        try {
            RunResult result = runner.run(new ExecutionRequest(id, prompt, settings), context);
            if (result.status() != ExecutionStatus.COMPLETE) throw new IOException(result.answer());
        } finally { context.control().close(); }
    }
}
