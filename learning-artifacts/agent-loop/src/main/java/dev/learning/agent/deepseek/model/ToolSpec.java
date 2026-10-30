package dev.learning.agent.deepseek.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ToolSpec(String type, FunctionSpec function) {
    public record FunctionSpec(String name, String description, ParameterSchema parameters) {}

    /** JSON Schema for one function's arguments. */
    public record ParameterSchema(String type, Map<String, PropertySchema> properties,
                                  List<String> required,
                                  @JsonProperty("additionalProperties") boolean additionalProperties) {
        public static ParameterSchema oneString(String field, String description) {
            return new ParameterSchema("object", Map.of(field, new PropertySchema("string", description)),
                    List.of(field), false);
        }
    }

    public record PropertySchema(String type, String description) {}
}
