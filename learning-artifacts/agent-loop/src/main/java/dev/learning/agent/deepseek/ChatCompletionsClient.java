package dev.learning.agent.deepseek;

import dev.learning.agent.core.runtime.RunControl;
import dev.learning.agent.core.runtime.ExternalObserver;
import dev.learning.agent.model.ModelStreamObserver;
import dev.learning.agent.deepseek.model.ChatRequest;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.model.ChatModel;
import dev.learning.agent.model.ModelRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/** Chat Completions adapter; run ownership stays outside the protocol layer. */
public final class ChatCompletionsClient implements ChatModel {
    private final ObjectMapper json;
    private final StreamingHttp http;

    public ChatCompletionsClient(ObjectMapper json, String key, String baseUrl) {
        this(json, key, baseUrl, ExternalObserver.NOOP);
    }

    public ChatCompletionsClient(ObjectMapper json, String key, String baseUrl, ExternalObserver observer) {
        this.json = json;
        this.http = new StreamingHttp(json, key, baseUrl, "/chat/completions", observer);
    }

    public AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control)
        throws IOException, InterruptedException {
        return stream(request, onText, control, ModelStreamObserver.NOOP);
    }

    public AssistantTurn stream(ModelRequest request, Consumer<String> onText, RunControl control,
        ModelStreamObserver observer) throws IOException, InterruptedException {
        try (InputStream input = http.send(ChatRequest.forModel(request), control)) {
            return new StreamAssembler(json, onText, observer).read(input);
        }
    }
}
