package dev.learning.agent.tool;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.runtime.BudgetExceededException;
import dev.learning.agent.core.runtime.Evidence;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.core.runtime.TraceEvent;
import dev.learning.agent.model.ToolCall;
import dev.learning.agent.support.json.StrictJson;

import java.util.Set;

/** Per-session tools, with evidence and budget ownership supplied by each run. */
public final class DefaultToolExecutionService implements ToolExecutionService {
    private final ToolRegistry registry;
    public DefaultToolExecutionService(ToolRegistry registry) { this.registry = registry; }

    public Evidence invoke(ToolInput input, String id, Set<String> allowed, RunScope scope, RunContext context) {
        return attempt(input.toolName(), StrictJson.write(input), id, allowed, scope, context);
    }

    public Evidence invokeNative(ToolCall call, String id, Set<String> allowed, RunScope scope, RunContext context) {
        return attempt(call.function().name(), call.function().arguments(), id, allowed, scope, context);
    }

    private Evidence attempt(String name, String arguments, String id, Set<String> allowed,
                             RunScope scope, RunContext context) {
        try { context.budget().claimToolAttempt(); }
        catch (BudgetExceededException e) { return skipped(name, arguments, id, scope, context, e.getMessage()); }
        String traceId = "tool-" + context.requestId() + "-" + context.budget().snapshot().toolAttempts();
        int round = context.budget().snapshot().modelCalls();
        long at = System.currentTimeMillis();
        long started = System.nanoTime();
        context.trace(scope, TraceEvent.toolStarted(traceId, round, at, name, arguments));
        context.emit("tool", "tool: " + name);
        ToolResult result;
        boolean dispatched = false;
        try {
            context.control().check();
            if (!allowed.contains(name) || registry.find(name) == null) {
                result = failure(ToolStatus.ERROR, "UNKNOWN_TOOL", "unknown tool " + name, false);
            } else {
                ToolInput input = ToolInputDecoder.decode(name, arguments);
                if (context.mode() == AgentMode.PLAN_EXECUTE && input instanceof BashInput
                        && context.evidence().snapshot().stream().anyMatch(item -> name.equals(item.tool())
                        && StrictJson.write(input).equals(item.boundArgumentsJson()))) {
                    result = failure(ToolStatus.DENIED, "DUPLICATE_BASH", "duplicate Bash request blocked in this run", false);
                } else result = cachedCalculator(input, context);
                if (result == null) {
                    dispatched = true;
                    result = registry.find(name).execute(input, scope, context);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = failure(ToolStatus.CANCELLED, "CANCELLED", "tool interrupted", dispatched);
        } catch (java.util.concurrent.TimeoutException e) {
            result = failure(ToolStatus.TIMEOUT, "TOOL_TIMEOUT", "tool timed out", dispatched);
        } catch (java.util.concurrent.CancellationException e) {
            result = failure(ToolStatus.CANCELLED, "CANCELLED", "tool cancelled", dispatched);
        } catch (java.io.IOException | IllegalArgumentException | ArithmeticException e) {
            result = failure(ToolStatus.ERROR, "TOOL_FAILED", "invalid tool arguments or operation: " + e.getMessage(), dispatched);
        } catch (Exception e) {
            result = failure(ToolStatus.ERROR, "TOOL_FAILED", "tool failed", dispatched);
        }
        context.budget().recordToolResult(result);
        Evidence evidence = save(name, arguments, id, scope, result, (System.nanoTime() - started) / 1_000_000, context);
        String status = result.status() == ToolStatus.SUCCESS ? "complete" : result.status().name().toLowerCase(java.util.Locale.ROOT);
        TraceEvent trace = new TraceEvent(traceId, "tool", status, round, at,
                evidence.durationMs(), name, arguments, evidence.result().content());
        context.trace(scope, trace);
        return evidence;
    }

    private ToolResult cachedCalculator(ToolInput input, RunContext context) {
        if (context.mode() != AgentMode.PLAN_EXECUTE || !(input instanceof CalculatorInput)) return null;
        String args = StrictJson.write(input);
        for (Evidence item : context.evidence().snapshot()) {
            ToolResult value = item.result();
            if ("calculator".equals(item.tool()) && args.equals(item.boundArgumentsJson())
                    && value.status() == ToolStatus.SUCCESS && value.hasValue() && !value.truncated()) {
                return new ToolResult(ToolStatus.SUCCESS, value.content(), null, false, true, false);
            }
        }
        return null;
    }

    public static Evidence skipped(String name, String args, String id, RunScope scope, RunContext context, String reason) {
        return save(name, args, id, scope, failure(ToolStatus.SKIPPED, "NOT_EXECUTED", reason, false), 0, context);
    }

    public static Evidence rejected(String name, String args, String id, RunScope scope, RunContext context, String reason) {
        return save(name, args, id, scope, failure(ToolStatus.ERROR, "BINDING_FAILED", reason, false), 0, context);
    }

    public static ToolResult failure(ToolStatus status, String code, String content, boolean executed) {
        return new ToolResult(status, content, code, executed, false, false);
    }

    private static Evidence save(String name, String args, String id, RunScope scope,
                                 ToolResult result, long duration, RunContext context) {
        int used = context.evidence().snapshot().stream().mapToInt(e -> e.result().content().length()).sum();
        int keep = Math.max(0, Math.min(8000, 32000 - used));
        if (result.content().length() > keep) {
            result = new ToolResult(result.status(), result.content().substring(0, keep), result.errorCode(),
                    result.executed(), result.hasValue(), true);
        }
        Evidence evidence = new Evidence(id, scope.stepId(), name, args, result, duration);
        context.evidence().put(evidence);
        context.emit("evidence", evidence);
        return evidence;
    }

    public static String modelContent(Evidence evidence) {
        return StrictJson.write(new ToolOutput(evidence.id(), evidence.result().status(), evidence.result().content(),
                evidence.result().executed(), evidence.result().hasValue(), evidence.result().truncated()));
    }

    record ToolOutput(String evidenceId, ToolStatus status, String content, boolean executed, boolean hasValue, boolean truncated) {}
}
