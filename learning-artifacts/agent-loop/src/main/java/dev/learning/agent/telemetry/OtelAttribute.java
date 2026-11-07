package dev.learning.agent.telemetry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares direct attribute mappings on observation records; it never creates a span. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface OtelAttribute {
    String value();

    String[] aliases() default {};
}
