package dev.learning.agent.core.planning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Prompts {
    private Prompts() {
    }

    public static String read(String name) {
        try (var input = Prompts.class.getResourceAsStream("/prompts/" + name + ".txt")) {
            if (input == null) {
                throw new IllegalStateException("missing prompt resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read prompt resource", e);
        }
    }
}
