package dev.learning.agent.app.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path directory;

    @Test
    void savedProtocolTakesEffectOnlyAfterRestart() throws Exception {
        Path file = directory.resolve("config/settings.json");
        SettingsStore first = new SettingsStore(new ObjectMapper(), file);
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, first.activeProtocol());
        var pending = first.save(ApiProtocol.RESPONSES);
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, pending.activeProtocol());
        assertEquals(ApiProtocol.RESPONSES, pending.savedProtocol());
        assertTrue(pending.restartRequired());
        SettingsStore restarted = new SettingsStore(new ObjectMapper(), file);
        assertEquals(ApiProtocol.RESPONSES, restarted.activeProtocol());
        assertFalse(restarted.status().restartRequired());
        assertTrue(first.save(ApiProtocol.ANTHROPIC).restartRequired());
        assertEquals(ApiProtocol.ANTHROPIC, new SettingsStore(new ObjectMapper(), file).activeProtocol());
    }

    @Test
    void invalidSettingsCannotSilentlySelectAnotherProtocol() throws Exception {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"protocol\":\"invalid\"}");
        assertThrows(IOException.class, () -> new SettingsStore(new ObjectMapper(), file));
    }

    @Test
    void foreignOriginCannotChangeSavedProtocol() throws Exception {
        SettingsStore store = new SettingsStore(new ObjectMapper(), directory.resolve("settings.json"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SettingsController(store)).build();
        mvc.perform(post("/api/settings").header("Origin", "http://other.example")
                        .contentType("application/json").content("{\"protocol\":\"RESPONSES\"}"))
                .andExpect(status().isForbidden());
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, store.status().savedProtocol());
    }
}
