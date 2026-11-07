package dev.learning.agent.observability;

import dev.learning.agent.telemetry.LangfuseAttributes;
import dev.learning.agent.telemetry.OtelAttribute;
import dev.learning.agent.telemetry.SpanAttributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compiles explicit record mappings once per class; it never serializes arbitrary objects. */
final class OtelAttributeWriter {
    private static final ClassValue<List<Binding>> MAPPINGS = new ClassValue<>() {
        @Override
        protected List<Binding> computeValue(Class<?> type) {
            return bindings(type);
        }
    };

    private OtelAttributeWriter() {
    }

    static void validateSchemas() {
        for (Class<?> group : List.of(SpanAttributes.class, LangfuseAttributes.class)) {
            for (Class<?> type : group.getDeclaredClasses()) {
                MAPPINGS.get(type);
            }
        }
    }

    static void write(Span span, SpanAttributes bean) {
        span.setAllAttributes(attributes(bean));
    }

    static Attributes attributes(SpanAttributes bean) {
        AttributesBuilder attributes = Attributes.builder();
        for (Binding binding : MAPPINGS.get(bean.getClass())) {
            Object value = read(binding.accessor(), bean);
            if (value != null) {
                put(attributes, binding.key(), value);
            }
        }
        return attributes.build();
    }

    private static List<Binding> bindings(Class<?> type) {
        if (!type.isRecord() || !SpanAttributes.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                "Attribute beans must be records implementing SpanAttributes: " + type.getName());
        }
        List<Binding> bindings = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            OtelAttribute annotation = component.getAnnotation(OtelAttribute.class);
            if (annotation == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            names.add(annotation.value());
            names.addAll(List.of(annotation.aliases()));
            for (String key : names) {
                if (key.isBlank() || !key.equals(key.trim()) || !keys.add(key)) {
                    throw new IllegalArgumentException("Blank, padded or duplicate attribute key: " + key);
                }
                bindings.add(new Binding(component.getAccessor(), key(component, key)));
            }
        }
        return List.copyOf(bindings);
    }

    private static AttributeKey<?> key(RecordComponent component, String name) {
        Class<?> type = component.getType();
        if (type == String.class) {
            return AttributeKey.stringKey(name);
        }
        if (type == long.class || type == Long.class || type == int.class || type == Integer.class) {
            return AttributeKey.longKey(name);
        }
        if (type == boolean.class || type == Boolean.class) {
            return AttributeKey.booleanKey(name);
        }
        if (type == double.class || type == Double.class) {
            return AttributeKey.doubleKey(name);
        }
        if (type == List.class && component.getGenericType() instanceof ParameterizedType list
            && list.getActualTypeArguments()[0] == String.class) {
            return AttributeKey.stringArrayKey(name);
        }
        throw new IllegalArgumentException("Unsupported attribute type: " + component);
    }

    private static Object read(Method accessor, SpanAttributes bean) {
        try {
            return accessor.invoke(bean);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException("Cannot read attribute: " + accessor, error);
        }
    }

    private static void put(AttributesBuilder attributes, AttributeKey<?> key, Object value) {
        switch (key.getType()) {
            case STRING -> attributes.put(AttributeKey.stringKey(key.getKey()), (String) value);
            case LONG -> attributes.put(AttributeKey.longKey(key.getKey()), ((Number) value).longValue());
            case BOOLEAN -> attributes.put(AttributeKey.booleanKey(key.getKey()), (Boolean) value);
            case DOUBLE -> attributes.put(AttributeKey.doubleKey(key.getKey()), (Double) value);
            case STRING_ARRAY -> attributes.put(AttributeKey.stringArrayKey(key.getKey()), strings(value));
            default -> throw new IllegalArgumentException("Unsupported attribute key: " + key);
        }
    }

    private static List<String> strings(Object value) {
        List<?> values = (List<?>) value;
        List<String> strings = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("String array attributes must contain non-null strings");
            }
            strings.add(text);
        }
        return strings;
    }

    private record Binding(Method accessor, AttributeKey<?> key) {
    }
}
