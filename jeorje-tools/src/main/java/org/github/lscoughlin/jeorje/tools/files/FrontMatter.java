package org.github.lscoughlin.jeorje.tools.files;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Finds the front matter of a file. Front matter is a block of YAML between two {@code ---}
 * lines. A comment prefix can come before each marker, so source code can carry it.
 */
public final class FrontMatter {
    /** The prefixes, longest first. The empty prefix means no comment prefix. */
    private static final List<String> PREFIXES = List.of("<!--", "/*", "//", "--", "#", "*", ";", "%", "");
    private static final YAMLMapper YAML = new YAMLMapper();

    private FrontMatter() {}

    /** The limits of a scan. */
    public record Limits(int scanLines, int maxLines) {
        public static Limits defaults() { return new Limits(25, 40); }
    }

    /**
     * Scans the lines of a file.
     *
     * @return the lines of the block without the markers and without the comment prefix, joined with
     *     new lines; empty if the file has no front matter
     */
    public static Optional<String> scan(List<String> lines, Limits limits) {
        var end = Math.min(limits.scanLines(), lines.size());
        for (var i = 0; i < end; i++) {
            var prefix = markerPrefix(lines.get(i));
            if (prefix == null) continue;
            var last = Math.min(lines.size() - 1, i + limits.maxLines() - 1);
            for (var j = i + 1; j <= last; j++) {
                if (!prefix.equals(markerPrefix(lines.get(j)))) continue;
                var block = strip(lines.subList(i + 1, j), prefix);
                if (isMapping(block)) return Optional.of(block);
                break;
            }
        }
        return Optional.empty();
    }

    /** The comment prefix of a marker line, or null if the line is not a marker. */
    static String markerPrefix(String line) {
        var text = line.stripLeading();
        for (var prefix : PREFIXES) {
            if (!text.startsWith(prefix)) continue;
            var rest = text.substring(prefix.length());
            var spaced = !rest.isEmpty() && Character.isWhitespace(rest.charAt(0));
            if (prefix.equals("--") && !spaced) continue;
            if (rest.strip().equals("---")) return prefix;
        }
        return null;
    }

    private static String strip(List<String> block, String prefix) {
        var out = new StringBuilder();
        for (var line : block) {
            var text = line.stripLeading();
            if (!prefix.isEmpty() && text.startsWith(prefix)) {
                text = text.substring(prefix.length());
                if (text.startsWith(" ")) text = text.substring(1);
            } else if (prefix.isEmpty()) {
                text = line;
            }
            out.append(text).append('\n');
        }
        if (!out.isEmpty()) out.setLength(out.length() - 1);
        return out.toString();
    }

    private static boolean isMapping(String yaml) {
        if (yaml.isBlank()) return false;
        try {
            JsonNode node = YAML.readTree(yaml);
            return node != null && node.isObject() && !node.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
