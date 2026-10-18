package org.github.lscoughlin.jeorje.core.compaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.config.ConfigException;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;

/**
 * The instructions for the compactor model. The built-in text can be appended to or replaced with
 * {@code prompts.compaction}. The output contract is fixed and always added.
 */
public final class CompactorPrompt {
    /** The built-in instructions. */
    public static final String BUILT_IN = """
        You prune text for a coding agent. Level ${level} keeps about ${target} of the characters of each chunk.
        The class of the chunks is ${class}.
        Keep the words that carry information: identifiers, file paths, numbers, error messages, and decisions.
        Remove filler words, repeated lines, and blank lines.
        Never write new words in a span answer. Choose spans of the original text only.
        Choose at most ${maxKeywords} keywords for each chunk. A keyword is an exact string from the chunk.
        Prefer identifiers, paths, error names, numbers, and rare words. Do not choose common words.
        """;

    /** The output contract. Jeorje always adds it. */
    public static final String CONTRACT = """

        Answer with one JSON object and nothing else:
        {"results":[{"id":"<chunk id>","spans":[[start,end],...],"keywords":["..."]}]}
        Each span is a pair of character offsets in the chunk text. The start is included and the end is not.
        Spans are in order and do not overlap. Give one result for each chunk of the request.
        For a CAREFUL chunk at level 2, give a decision note instead of spans:
        {"id":"<chunk id>","note":{"decision":["..."],"reason":["..."],"rejected":["..."],\
        "open question":["..."],"files":["<path>"]},"keywords":["..."]}
        Each bullet is short and uses words of the chunk. Each file and each keyword is an exact string of the chunk.
        """;

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(\\w+)}");

    private CompactorPrompt() {}

    /**
     * Builds the system prompt.
     *
     * @param prompt the config value, or null for the built-in text
     * @param baseDir the directory of the config file; a relative prompt file starts here
     */
    public static String build(JeorjeConfig.Prompt prompt, Path baseDir, int level, double target,
                               Protection protection, int maxKeywords) {
        var vars = Map.of("level", Integer.toString(level),
            "target", String.format(Locale.ROOT, "%d%%", Math.round(target * 100)),
            "class", protection.name(), "maxKeywords", Integer.toString(maxKeywords));
        var custom = customText(prompt, baseDir);
        String text;
        if (custom == null) {
            text = BUILT_IN;
        } else if ("append".equalsIgnoreCase(prompt.mode())) {
            text = BUILT_IN + "\n" + custom;
        } else {
            text = custom;
        }
        return substitute(text, vars) + CONTRACT;
    }

    private static String customText(JeorjeConfig.Prompt p, Path baseDir) {
        if (p == null) return null;
        var hasText = p.text() != null && !p.text().isEmpty();
        var hasFile = p.file() != null && !p.file().isEmpty();
        if (hasText && hasFile) throw new ConfigException("prompts.compaction has both text and file");
        if (hasText) return p.text();
        if (!hasFile) return null;
        var file = Path.of(p.file());
        if (!file.isAbsolute() && baseDir != null) file = baseDir.resolve(file);
        try {
            var s = Files.readString(file, StandardCharsets.UTF_8);
            if (s.isBlank()) throw new ConfigException("prompts.compaction file is empty: " + file);
            return s;
        } catch (IOException e) {
            throw new ConfigException("prompts.compaction file cannot be read: " + file, e);
        }
    }

    private static String substitute(String text, Map<String, String> vars) {
        var m = VARIABLE.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            var v = vars.get(m.group(1));
            if (v == null) throw new ConfigException("prompts.compaction has an unknown variable: " + m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
