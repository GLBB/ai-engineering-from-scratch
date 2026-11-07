package dev.learning.agent.core.runtime;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.ExecutionRequest;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.DefaultModelGateway;
import dev.learning.agent.tool.DefaultToolExecutionService;
import dev.learning.agent.tool.ToolRegistry;

import java.time.Duration;
import java.util.UUID;

public final class RunContexts {
    private RunContexts() {}
    public static RunContext create(UUID id, AgentMode mode, ChatModel client, ToolRegistry tools,
                                    RunEvents events, ApprovalGateway approvals) {
        return create(id, mode, client, tools, events, approvals, RunTelemetry.NOOP);
    }
    public static RunContext create(UUID id, AgentMode mode, ChatModel client, ToolRegistry tools,
                                    RunEvents events, ApprovalGateway approvals, RunTelemetry telemetry) {
        return new DefaultRunContext(id, mode, new DefaultRunBudget(mode), new DefaultRunControl(Duration.ofMinutes(15)),
                events, new InMemoryEvidenceStore(), new DefaultModelGateway(client),
                new DefaultToolExecutionService(tools), approvals, telemetry);
    }
    public static void start(ExecutionRequest request, RunContext context) {
        long at = System.currentTimeMillis();
        RunScope scope = new RunScope("INPUT", null, 0);
        context.trace(scope, TraceEvent.user("user-" + context.requestId(), request.prompt(), at));
        String protocol = context.model().protocol();
        context.trace(scope,
                TraceEvent.settings("settings-" + context.requestId(), request.settings(), protocol, at));
    }
}
