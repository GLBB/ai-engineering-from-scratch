package dev.learning.agent.telemetry;

/** Backend-specific direct mappings; content filtering and usage calculations stay in their policies. */
public interface LangfuseAttributes extends SpanAttributes {
    record Context(@OtelAttribute("langfuse.observation.type") String type,
        @OtelAttribute("langfuse.session.id") String sessionId, @OtelAttribute("langfuse.trace.name") String traceName,
        @OtelAttribute("langfuse.environment") String environment, @OtelAttribute("langfuse.release") String release,
        @OtelAttribute(value = "langfuse.trace.metadata.mode",
            aliases = "langfuse.observation.metadata.mode") String mode,
        @OtelAttribute(value = "langfuse.trace.metadata.protocol",
            aliases = "langfuse.observation.metadata.protocol") String protocol,
        @OtelAttribute(value = "langfuse.trace.metadata.requestId",
            aliases = "langfuse.observation.metadata.requestId") String requestId)
        implements
            LangfuseAttributes {
    }

    record Phase(@OtelAttribute("langfuse.observation.metadata.phase") String phase,
        @OtelAttribute("langfuse.observation.metadata.stepId") String stepId,
        @OtelAttribute("langfuse.observation.metadata.revision") long revision) implements LangfuseAttributes {
    }

    record Input(@OtelAttribute("langfuse.observation.input") String json) implements LangfuseAttributes {
    }

    record Output(@OtelAttribute("langfuse.observation.output") String json) implements LangfuseAttributes {
    }

    record Level(@OtelAttribute("langfuse.observation.level") String level) implements LangfuseAttributes {
    }

    record CompletionStart(
        @OtelAttribute("langfuse.observation.completion_start_time") String time) implements LangfuseAttributes {
    }

    record UsageDetails(
        @OtelAttribute("langfuse.observation.usage_details") String json) implements LangfuseAttributes {
    }
}
