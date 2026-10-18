package org.github.lscoughlin.jeorje.ui.chat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;

/**
 * Maps a fence tag or a file name to a syntax. The editor and the chat view share this class.
 *
 * <p>Steps for a tag: a syntax name, an alias, and the file name test {@code x.<tag>}.
 */
public final class LanguageResolver {

    /** The built-in alias table. A value is a syntax name or a file extension. */
    static final Map<String, String> BUILT_IN = Map.ofEntries(
            Map.entry("js", "js"), Map.entry("ts", "ts"), Map.entry("py", "py"), Map.entry("sh", "sh"),
            Map.entry("bash", "sh"), Map.entry("shell", "sh"), Map.entry("zsh", "zsh"), Map.entry("yml", "yaml"),
            Map.entry("rs", "rs"), Map.entry("golang", "go"), Map.entry("c++", "cpp"), Map.entry("cpp", "cpp"),
            Map.entry("cs", "cs"), Map.entry("kt", "kt"), Map.entry("rb", "rb"), Map.entry("md", "md"),
            Map.entry("jsonc", "json"), Map.entry("tf", "tf"), Map.entry("dockerfile", "Dockerfile"));

    private final SyntaxCatalog catalog;
    private final Map<String, String> aliases = new HashMap<>(BUILT_IN);

    public LanguageResolver(SyntaxCatalog catalog, Map<String, String> extraAliases) {
        this.catalog = catalog;
        extraAliases.forEach((k, v) -> aliases.put(k.toLowerCase(Locale.ROOT), v));
    }

    /**
     * Takes the tag out of a fence info string. Accepts {@code java}, {@code {java}}, {@code {.java}}, and
     * {@code {r, echo=FALSE}}. Returns an empty string when there is no tag.
     */
    public static String tagOf(String info) {
        if (info == null) {
            return "";
        }
        var text = info.strip();
        if (text.startsWith("{")) {
            text = text.substring(1);
        }
        if (text.endsWith("}")) {
            text = text.substring(0, text.length() - 1);
        }
        text = text.strip();
        if (text.startsWith(".")) {
            text = text.substring(1);
        }
        var end = 0;
        while (end < text.length()) {
            var c = text.charAt(end);
            if (Character.isWhitespace(c) || c == ',' || c == '=') {
                break;
            }
            end++;
        }
        return text.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /** Resolves the info string of a fence. */
    public Optional<SyntaxInfo> resolve(String info) {
        var tag = tagOf(info);
        if (tag.isEmpty()) {
            return Optional.empty();
        }
        var byName = catalog.byName(tag);
        if (byName.isPresent()) {
            return byName;
        }
        var alias = aliases.get(tag);
        if (alias != null) {
            var viaAlias = catalog.byName(alias).or(() -> catalog.forFileName("x." + alias))
                    .or(() -> catalog.forFileName(alias));
            if (viaAlias.isPresent()) {
                return viaAlias;
            }
        }
        return catalog.forFileName("x." + tag);
    }

    /** Selects the syntax for a file name or path. */
    public Optional<SyntaxInfo> forFile(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        var normalized = path.replace('\\', '/');
        var base = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (base.equalsIgnoreCase("llms.txt")) {
            return catalog.byName("markdown");
        }
        return catalog.forFileName(path);
    }
}
