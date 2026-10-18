package org.github.lscoughlin.jeorje.tools.registry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Limits a string field to a fixed set of values. {@link SchemaGenerator} writes an {@code enum}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Allowed {
    String[] value();
}
