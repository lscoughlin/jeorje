package org.github.lscoughlin.jeorje.core.config;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Removes secrets from text. Use it for logs, the audit log, and the index. It removes the known
 * secret values and the common key patterns.
 */
public final class SecretScrubber {

    public static final String MASK = "***";

    private static final int MIN_SECRET_LENGTH = 6;

    private static final Pattern[] PATTERNS = {
            Pattern.compile("sk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{16,}"),
    };

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\b[\\w.-]*(?:api[_-]?key|token|secret|password|passwd)[\\w.-]*[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;]+)");

    private final Set<String> secrets = new CopyOnWriteArraySet<>();

    public SecretScrubber() {
    }

    public SecretScrubber(Collection<String> knownSecrets) {
        knownSecrets.forEach(this::register);
    }

    /** Adds a secret value. Values that are too short are ignored, because they would damage text. */
    public SecretScrubber register(String secret) {
        if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
            secrets.add(secret);
        }
        return this;
    }

    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        var out = text;
        for (var secret : secrets.stream().sorted((a, b) -> b.length() - a.length()).toList()) {
            out = out.replace(secret, MASK);
        }
        for (var pattern : PATTERNS) {
            out = pattern.matcher(out).replaceAll(m -> m.groupCount() > 0 && m.group(1) != null
                    ? java.util.regex.Matcher.quoteReplacement(m.group(1)) + MASK : MASK);
        }
        return ASSIGNMENT.matcher(out).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(m.group(1)) + MASK);
    }

    /**
     * Scrubs every text value of a JSON tree, in place. Field names stay. Scrubbing the values one by one keeps
     * the JSON valid, which scrubbing the written text would not always do.
     *
     * @return the same node
     */
    public <T extends JsonNode> T scrubTree(T node) {
        scrubChildren(node);
        return node;
    }

    private void scrubChildren(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (var name : java.util.List.copyOf(object.propertyNames())) {
                var child = object.get(name);
                if (child.isString()) {
                    object.set(name, StringNode.valueOf(scrub(child.asString())));
                } else {
                    scrubChildren(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (var i = 0; i < array.size(); i++) {
                var child = array.get(i);
                if (child.isString()) {
                    array.set(i, StringNode.valueOf(scrub(child.asString())));
                } else {
                    scrubChildren(child);
                }
            }
        }
    }
}
