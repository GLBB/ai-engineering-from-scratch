package dev.learning.agent.observability;

import dev.learning.agent.core.runtime.TraceSpan;
import dev.learning.agent.telemetry.LangfuseAttributes;
import dev.learning.agent.telemetry.OtelAttribute;
import dev.learning.agent.telemetry.SpanAttributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OtelAttributeWriterTest {
    @Test
    void allProductionSchemasCanBeValidatedBeforeRunningAnOperation() {
        assertDoesNotThrow(OtelAttributeWriter::validateSchemas);
    }

    @Test
    void mapsDeclaredTypesWithoutStringifyingNumbersOrBooleans() {
        var attributes = OtelAttributeWriter.attributes(new ScalarAttributes("model", 12, 0L, false, 0.25));
        assertEquals("model", attributes.get(AttributeKey.stringKey("test.model")));
        assertEquals(12L, attributes.get(AttributeKey.longKey("test.count")));
        assertEquals(0L, attributes.get(AttributeKey.longKey("test.tokens")));
        assertEquals(false, attributes.get(AttributeKey.booleanKey("test.complete")));
        assertEquals(0.25, attributes.get(AttributeKey.doubleKey("test.ratio")));
    }

    @Test
    void omitsUnknownUsageButPreservesKnownZeroUsage() {
        var attributes = OtelAttributeWriter.attributes(new SpanAttributes.TokenUsage(null, 0L, null, null, null));
        assertEquals(1, attributes.size());
        assertNull(attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(0L, attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    }

    @Test
    void onlyAnnotatedComponentsAreReadAndExported() {
        Object secret = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Unmapped content must never be serialized");
            }
        };
        var attributes = OtelAttributeWriter.attributes(new SelectiveAttributes("safe", secret));
        assertEquals(1, attributes.size());
        assertEquals("safe", attributes.get(AttributeKey.stringKey("test.allowed")));
    }

    @Test
    void aliasesKeepLangfuseTraceAndObservationMetadataConsistent() {
        var attributes = OtelAttributeWriter.attributes(
            new LangfuseAttributes.Context("agent", "session", "run", "local", "release", "REACT", "test", "request"));
        for (String key : List.of("mode", "protocol", "requestId")) {
            assertEquals(attributes.get(AttributeKey.stringKey("langfuse.trace.metadata." + key)),
                attributes.get(AttributeKey.stringKey("langfuse.observation.metadata." + key)));
        }
        assertEquals("REACT", attributes.get(AttributeKey.stringKey("langfuse.trace.metadata.mode")));
    }

    @Test
    void arraysRemainArraysForFinishReasonsAndEvidence() {
        var attributes = OtelAttributeWriter.attributes(new SpanAttributes.StepResult("COMPLETE", List.of("E1", "E2")));
        assertEquals(List.of("E1", "E2"), attributes.get(AttributeKey.stringArrayKey("agent_loop.step.evidence_ids")));
        var empty = OtelAttributeWriter.attributes(new SpanAttributes.StepResult("SKIPPED", List.of()));
        assertEquals(List.of(), empty.get(AttributeKey.stringArrayKey("agent_loop.step.evidence_ids")));
    }

    @Test
    void nullUpdatesDoNotEraseAttributesAlreadyRecordedOnTheSpan() {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()) {
            var span = sdk.getTracer("attribute-test").spanBuilder("run").startSpan();
            OtelAttributeWriter.write(span, new SpanAttributes.RunResult("FAILED", "UNVERIFIED", "EXECUTION_ERROR"));
            OtelAttributeWriter.write(span, new SpanAttributes.RunResult("CANCELLED", null, null));
            span.end();
            var attributes = exporter.getFinishedSpanItems().get(0).getAttributes();
            assertEquals("CANCELLED", attributes.get(AttributeKey.stringKey("agent_loop.run.status")));
            assertEquals("UNVERIFIED", attributes.get(AttributeKey.stringKey("agent_loop.run.completion_check")));
            assertEquals("EXECUTION_ERROR", attributes.get(AttributeKey.stringKey("agent_loop.run.error_code")));
        }
    }

    @Test
    void rejectsAmbiguousOrEmptyAttributeMappings() {
        assertThrows(IllegalArgumentException.class,
            () -> OtelAttributeWriter.attributes(new DuplicateAttributes("a", "b")));
        assertThrows(IllegalArgumentException.class,
            () -> OtelAttributeWriter.attributes(new BlankAttributes("value")));
        assertThrows(IllegalArgumentException.class, () -> OtelAttributeWriter.attributes(new PaddedMapping("value")));
    }

    @Test
    void rejectsUnsupportedObjectsAndListElementTypesInsteadOfExportingTheirText() {
        assertThrows(IllegalArgumentException.class,
            () -> OtelAttributeWriter.attributes(new ObjectAttributes(new Object())));
        assertThrows(IllegalArgumentException.class,
            () -> OtelAttributeWriter.attributes(new NumericListAttributes(List.of(1))));
        assertThrows(IllegalArgumentException.class,
            () -> OtelAttributeWriter.attributes(new SpanAttributes.StepResult("COMPLETE", Arrays.asList("E1", null))));
    }

    @Test
    void repeatedAndConcurrentWritesKeepValuesBoundToTheirOwnBean() {
        List<String> modes = List.of("REACT", "REWOO", "PLAN_EXECUTE");
        List<String> observed = modes.parallelStream()
            .map(mode -> OtelAttributeWriter
                .attributes(new SpanAttributes.RunContext("session", "revision", "request", mode, "test"))
                .get(AttributeKey.stringKey("agent_loop.mode")))
            .toList();
        assertEquals(modes, observed);
    }

    @Test
    void noopDoesNotInspectOrSerializeAttributeBeans() {
        assertDoesNotThrow(() -> TraceSpan.NOOP.attributes(new ObjectAttributes(new Object())));
        assertFalse(TraceSpan.NOOP instanceof OtelSpan);
    }

    public record ScalarAttributes(@OtelAttribute("test.model") String model, @OtelAttribute("test.count") int count,
        @OtelAttribute("test.tokens") Long tokens, @OtelAttribute("test.complete") Boolean complete,
        @OtelAttribute("test.ratio") double ratio) implements SpanAttributes {
    }

    public record SelectiveAttributes(@OtelAttribute("test.allowed") String allowed,
        Object privateContent) implements SpanAttributes {
    }

    public record DuplicateAttributes(@OtelAttribute("test.same") String first,
        @OtelAttribute("test.same") String second) implements SpanAttributes {
    }

    public record BlankAttributes(@OtelAttribute(" ") String value) implements SpanAttributes {
    }

    public record PaddedMapping(@OtelAttribute(" test.padded ") String value) implements SpanAttributes {
    }

    public record ObjectAttributes(@OtelAttribute("test.object") Object value) implements SpanAttributes {
    }

    public record NumericListAttributes(@OtelAttribute("test.list") List<Integer> value) implements SpanAttributes {
    }
}
