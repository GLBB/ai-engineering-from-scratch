package dev.learning.agent.tool;

import java.net.URI;

/** Attribution metadata is rendered by the UI independently of model prose. */
public record ToolSource(String label, String url) {
    public ToolSource {
        if (label == null || label.isBlank() || url == null || !"https".equals(URI.create(url).getScheme()))
            throw new IllegalArgumentException("source requires a label and HTTPS URL");
    }
}
