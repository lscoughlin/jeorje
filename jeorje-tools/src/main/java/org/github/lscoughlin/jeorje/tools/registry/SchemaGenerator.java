package org.github.lscoughlin.jeorje.tools.registry;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes a JSON Schema from an input record. It handles strings, numbers, booleans, enums, lists,
 * maps, nested records, and {@link JsonNode} (any value). The annotations {@link Describe},
 * {@link Required}, and {@link Allowed} add details.
 */
public final class SchemaGenerator {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SchemaGenerator() {
    }

    /** Makes the schema of a record type. */
    public static ObjectNode forRecord(Class<?> type) {
        if (!type.isRecord()) throw new IllegalArgumentException(type.getName() + " is not a record");
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        var required = JSON.createArrayNode();
        for (var component : type.getRecordComponents()) {
            var property = forType(component.getGenericType());
            var describe = component.getAnnotation(Describe.class);
            if (describe != null) property.put("description", describe.value());
            var allowed = component.getAnnotation(Allowed.class);
            if (allowed != null) {
                var values = property.putArray("enum");
                for (var value : allowed.value()) values.add(value);
            }
            properties.set(component.getName(), property);
            if (component.isAnnotationPresent(Required.class)) required.add(component.getName());
        }
        if (!required.isEmpty()) schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode forType(Type type) {
        var node = JSON.createObjectNode();
        if (type instanceof ParameterizedType parameterized) {
            var raw = (Class<?>) parameterized.getRawType();
            var arguments = parameterized.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                node.put("type", "array");
                node.set("items", forType(arguments[0]));
            } else if (Map.class.isAssignableFrom(raw)) {
                node.put("type", "object");
                node.set("additionalProperties", forType(arguments[1]));
            }
            return node;
        }
        if (!(type instanceof Class<?> c)) return node;
        if (c == String.class || c == CharSequence.class || c == java.nio.file.Path.class) {
            node.put("type", "string");
        } else if (c == int.class || c == Integer.class || c == long.class || c == Long.class
                || c == short.class || c == Short.class) {
            node.put("type", "integer");
        } else if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            node.put("type", "number");
        } else if (c == boolean.class || c == Boolean.class) {
            node.put("type", "boolean");
        } else if (c.isEnum()) {
            node.put("type", "string");
            var values = node.putArray("enum");
            for (var constant : c.getEnumConstants()) values.add(((Enum<?>) constant).name());
        } else if (c.isRecord()) {
            return forRecord(c);
        } else if (c.isArray()) {
            node.put("type", "array");
            node.set("items", forType(c.getComponentType()));
        } else if (Collection.class.isAssignableFrom(c)) {
            node.put("type", "array");
        } else if (Map.class.isAssignableFrom(c)) {
            node.put("type", "object");
        }
        return node;
    }
}
