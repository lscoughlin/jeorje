package org.github.lscoughlin.jeorje.core.port;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Checks a JSON value against the part of JSON Schema that tool inputs use: {@code type},
 * {@code required}, {@code properties}, {@code additionalProperties}, {@code items}, {@code enum},
 * {@code minimum}, {@code maximum}, {@code minLength}, and {@code maxLength}.
 */
public final class InputValidator {
    private InputValidator() {
    }

    /** Returns the error messages. The list is empty when the input is valid. */
    public static List<String> validate(JsonNode schema, JsonNode input) {
        var errors = new ArrayList<String>();
        if (schema != null && !schema.isMissingNode()) check(schema, input, "input", errors);
        return errors;
    }

    private static void check(JsonNode schema, JsonNode value, String where, List<String> errors) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            errors.add(where + " must not be null");
            return;
        }
        var type = schema.path("type").asString("");
        var typeOk = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
        if (!typeOk) {
            errors.add(where + " must be of type " + type + " but is " + describe(value));
            return;
        }
        var allowed = schema.path("enum");
        if (allowed.isArray()) {
            var found = false;
            var names = new ArrayList<String>();
            for (var option : allowed) {
                names.add(option.asString());
                if (option.equals(value)) found = true;
            }
            if (!found) errors.add(where + " must be one of " + names + " but is " + value.asString());
        }
        if (value.isObject()) checkObject(schema, value, where, errors);
        if (value.isArray()) {
            var items = schema.path("items");
            if (items.isObject()) {
                var i = 0;
                for (var element : value) check(items, element, where + "[" + i++ + "]", errors);
            }
        }
        if (value.isNumber()) {
            var min = schema.path("minimum");
            var max = schema.path("maximum");
            if (min.isNumber() && value.asDouble() < min.asDouble()) {
                errors.add(where + " must be at least " + min.asString());
            }
            if (max.isNumber() && value.asDouble() > max.asDouble()) {
                errors.add(where + " must be at most " + max.asString());
            }
        }
        if (value.isString()) {
            var min = schema.path("minLength");
            var max = schema.path("maxLength");
            var length = value.asString().length();
            if (min.isIntegralNumber() && length < min.asInt()) {
                errors.add(where + " must have at least " + min.asInt() + " characters");
            }
            if (max.isIntegralNumber() && length > max.asInt()) {
                errors.add(where + " must have at most " + max.asInt() + " characters");
            }
        }
    }

    private static void checkObject(JsonNode schema, JsonNode value, String where, List<String> errors) {
        var required = schema.path("required");
        if (required.isArray()) {
            for (var name : required) {
                var field = value.get(name.asString());
                if (field == null || field.isNull()) errors.add(where + "." + name.asString() + " is required");
            }
        }
        var properties = schema.path("properties");
        var additional = schema.path("additionalProperties");
        for (var name : value.propertyNames()) {
            var field = value.get(name);
            var child = properties.path(name);
            if (child.isObject()) {
                if (!field.isNull()) check(child, field, where + "." + name, errors);
            } else if (additional.isBoolean() && !additional.asBoolean()) {
                errors.add(where + "." + name + " is not a known field");
            } else if (additional.isObject()) {
                check(additional, field, where + "." + name, errors);
            }
        }
    }

    private static String describe(JsonNode value) {
        return switch (value.getNodeType()) {
            case OBJECT -> "object";
            case ARRAY -> "array";
            case STRING -> "string";
            case BOOLEAN -> "boolean";
            case NUMBER -> value.isIntegralNumber() ? "integer" : "number";
            default -> "null";
        };
    }
}
