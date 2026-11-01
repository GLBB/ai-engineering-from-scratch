package dev.learning.agent.tool;

import dev.learning.agent.model.ToolCall;
import dev.learning.agent.model.ToolSpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public void register(AgentTool tool) {
        String name = tool.definition().function().name();
        if (tools.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("duplicate tool: " + name);
    }

    public List<ToolSpec> definitions() { return tools.values().stream().map(AgentTool::definition).toList(); }
    public AgentTool find(String name) { return tools.get(name); }

    public String dispatch(ToolCall call) {
        AgentTool tool = tools.get(call.function().name());
        if (tool == null) {
            LOG.warn("Unknown tool requested: {}", call.function().name());
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
