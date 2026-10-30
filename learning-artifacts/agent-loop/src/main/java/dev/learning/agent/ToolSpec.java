package dev.learning.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

record ToolSpec(String type, FunctionSpec function) {}

record FunctionSpec(String name, String description, ParameterSchema parameters) {}

/** A named Java type for the tool parameter JSON Schema. */
record ParameterSchema(String type, Map<String, PropertySchema> properties,
                       List<String> required,
                       @JsonProperty("additionalProperties") boolean additionalProperties) {
    static ParameterSchema oneString(String field, String description) {
        return new ParameterSchema("object", Map.of(field, new PropertySchema("string", description)),
                List.of(field), false);
    }
}

record PropertySchema(String type, String description) {}
