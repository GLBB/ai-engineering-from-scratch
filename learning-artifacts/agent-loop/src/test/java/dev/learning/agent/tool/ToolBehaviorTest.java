package dev.learning.agent.tool;

import dev.learning.agent.model.ToolCall.FunctionCall;
import dev.learning.agent.model.ToolCall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolBehaviorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void declinedBashCommandDoesNotRun(@TempDir Path directory) throws Exception {
        Path marker = directory.resolve("marker");
        BashTool bash = new BashTool(json, directory, ignored -> false, ignored -> true);
        String args = json.writeValueAsString(new BashTool.BashArgs("touch " + marker));
        assertEquals("error: user declined Bash command", bash.execute(args));
        assertFalse(java.nio.file.Files.exists(marker));
    }

    @Test
    void bashOutputRequiresSeparateSharingApproval(@TempDir Path directory) throws Exception {
        BashTool bash = new BashTool(json, directory, ignored -> true, ignored -> false);
        assertEquals("error: user declined sharing Bash output", bash.execute("{\"command\":\"printf 42\"}"));
    }

    @Test
    void approvedBashCommandReturnsBoundedResult(@TempDir Path directory) throws Exception {
        BashTool bash = new BashTool(json, directory, ignored -> true, ignored -> true);
        assertEquals("exit=0\n42", bash.execute("{\"command\":\"printf 42\"}"));
    }

    @Test
    void searchReportsMissingKeyWithoutNetwork() throws Exception {
        SearchTool search = new SearchTool(json, null);
        assertEquals("error: search unavailable; set EXA_AI_KEY", search.execute("{\"query\":\"agent loop\"}"));
    }

    @Test
    void typedToolArgumentsAreValidated() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool(json));
        assertEquals("46", tools.dispatch(
            new ToolCall("call-1", "function", new FunctionCall("calculator", "{\"expression\":\"(120+18)/3\"}"))));
        assertTrue(tools
            .dispatch(new ToolCall("call-2", "function", new FunctionCall("calculator", "{\"expression\":\"1/0\"}")))
            .startsWith("error:"));
    }
}
