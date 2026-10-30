package dev.learning.agent.tool;

import dev.learning.agent.deepseek.model.ToolCall;
import dev.learning.agent.deepseek.model.ToolSpec;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class ToolRegistry {
    private static final Logger LOG = Logger.getLogger(ToolRegistry.class.getName());
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public void register(AgentTool tool) {
        String name = tool.definition().function().name();
        if (tools.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("duplicate tool: " + name);
    }

    public List<ToolSpec> definitions() { return tools.values().stream().map(AgentTool::definition).toList(); }

    public String dispatch(ToolCall call) {
        AgentTool tool = tools.get(call.function().name());
        if (tool == null) {
            LOG.warning("Unknown tool requested: " + call.function().name());
            return "error: unknown tool " + call.function().name();
        }
        try {
            return tool.execute(call.function().arguments());
        } catch (JsonProcessingException e) {
            return "error: invalid tool arguments or operation: " + e.getOriginalMessage();
        } catch (IllegalArgumentException | ArithmeticException e) {
            return "error: invalid tool arguments or operation: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error: tool interrupted";
        } catch (IOException e) {
            return "error: tool I/O failure";
        } catch (Exception e) {
            return "error: tool failed";
        }
    }
}
