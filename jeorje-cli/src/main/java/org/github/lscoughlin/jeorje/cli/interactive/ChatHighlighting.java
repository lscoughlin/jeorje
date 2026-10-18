package org.github.lscoughlin.jeorje.cli.interactive;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.ui.chat.ChatHighlightConfig;
import org.github.lscoughlin.jeorje.ui.chat.HighlightContext;
import org.github.lscoughlin.jeorje.ui.chat.LanguageResolver;
import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the highlighting state of the chat view from the configuration. */
final class ChatHighlighting {
    private static final Logger log = LoggerFactory.getLogger(ChatHighlighting.class);

    private ChatHighlighting() {}

    /** True when the environment asks for no colors. An empty {@code NO_COLOR} does not count. */
    static boolean noColor(Map<String, String> env) {
        var value = env.get("NO_COLOR");
        return value != null && !value.isEmpty();
    }

    /** The settings from the keys of {@code ui.chat.highlight}, {@code editor.syntax.enabled}, and the environment. */
    static ChatHighlightConfig settings(JeorjeConfig config, Map<String, String> env, int colors) {
        var h = config.ui().chat().highlight();
        var map = new LinkedHashMap<String, Object>();
        map.put("enabled", h.enabled());
        map.put("maxLines", h.maxLines());
        map.put("diff", h.diff());
        map.put("read", h.read());
        map.put("fences", h.fences());
        map.put("aliases", h.aliases());
        return ChatHighlightConfig.fromMap(map)
                .withEditorSyntaxEnabled(config.editor().syntax().enabled())
                .withNoColor(noColor(env))
                .withFewColors(colors >= 0 && colors < 256);
    }

    /**
     * Builds the highlighting state, or returns null when the syntax catalog cannot load. The chat view then shows
     * plain text.
     *
     * @param colors the number of colors of the terminal, or -1 when it is not known
     */
    static HighlightContext build(JeorjeConfig config, Map<String, String> env, Path cwd, Path home, int colors) {
        var settings = settings(config, env, colors);
        try {
            var syntax = config.editor().syntax();
            var catalogSettings = SyntaxSettings.defaults(cwd, home, SyntaxCatalog.currentVersion())
                    .withConfigDirs(syntax.dirs().stream().map(Path::of).toList())
                    .withEmbedded(syntax.embedded());
            if (!syntax.systemDirs()) catalogSettings = catalogSettings.withSystemDirs(List.of());
            var catalog = SyntaxCatalog.load(catalogSettings);
            return new HighlightContext(settings, new LanguageResolver(catalog, settings.aliases()),
                    HighlighterFactory.jline());
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "No colors in the chat view: " + e);
            return null;
        }
    }
}
