package dev.learning.agent.app.settings;

import jakarta.servlet.http.HttpServletRequest;
import dev.learning.agent.app.LocalRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
final class SettingsController {
    private final SettingsStore settings;

    SettingsController(SettingsStore settings) { this.settings = settings; }

    @GetMapping("/api/settings")
    ResponseEntity<?> get(HttpServletRequest request) throws IOException {
        if (!LocalRequest.isLocalHost(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(settings.status());
    }

    @PostMapping("/api/settings")
    ResponseEntity<?> save(@RequestBody SettingsStore.Settings body, HttpServletRequest request) throws IOException {
        if (!LocalRequest.hasSameOrigin(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (body.protocol() == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(settings.save(body.protocol()));
    }
}
