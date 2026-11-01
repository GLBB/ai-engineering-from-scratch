package dev.learning.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.agent.core.ChatModel;
import dev.learning.agent.core.ModelRequest;
import dev.learning.agent.core.RunControl;
import dev.learning.agent.deepseek.model.AssistantTurn;
import dev.learning.agent.deepseek.model.ChatRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/** Chat Completions adapter; run ownership stays outside the protocol layer. */
public final class ChatCompletionsClient implements ChatModel {
    private final ObjectMapper json;
    private final StreamingHttp http;

    public ChatCompletionsClient(ObjectMapper json, String key, String baseUrl) {
        this.json = json;
        this.http = new StreamingHttp(json, key, baseUrl, "/chat/completions");
    }

    public AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control)
            throws IOException, InterruptedException {
        try (InputStream input = http.send(ChatRequest.forModel(request), control)) {
            return new StreamAssembler(json, onText).read(input);
        }
    }
}
