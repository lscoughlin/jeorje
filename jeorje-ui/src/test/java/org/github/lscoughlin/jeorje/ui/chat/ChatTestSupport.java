package org.github.lscoughlin.jeorje.ui.chat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxCatalog;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxSettings;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/** Helpers for the chat tests. */
final class ChatTestSupport {

    private ChatTestSupport() {
    }

    static final ChatHighlightConfig CONFIG = ChatHighlightConfig.defaults().withNoColor(false);

    static SyntaxCatalog catalog(Path tmp) {
        return SyntaxCatalog.load(new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), "chat"));
    }

    static HighlightContext context(Path tmp, ChatHighlightConfig config) {
        return new HighlightContext(config, new LanguageResolver(catalog(tmp), config.aliases()),
                HighlighterFactory.jline());
    }

    static HighlightContext context(Path tmp, ChatHighlightConfig config, HighlighterFactory factory) {
        return new HighlightContext(config, new LanguageResolver(catalog(tmp), config.aliases()), factory);
    }

    static boolean colored(AttributedString s) {
        return s.toAnsi().contains("\u001b[");
    }

    /** True when some character of the line has a background color. */
    static boolean tinted(AttributedString s) {
        return s.toAnsi().matches("(?s).*\u001b\\[[0-9;]*48;.*");
    }

    static boolean bold(AttributedString s, int index) {
        return s.substring(index, index + 1).toAnsi().matches("(?s)\u001b\\[([0-9;]*;)?1(;[0-9;]*)?m.*");
    }

    /** A fake factory. Each highlighter records the lines it gets and marks them with a style. */
    static class RecordingFactory implements HighlighterFactory {
        final List<Recorder> created = new ArrayList<>();
        final Map<String, Boolean> unused = Map.of();

        @Override
        public LineHighlighter create(org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo syntax) {
            var recorder = new Recorder(syntax.name());
            created.add(recorder);
            return recorder;
        }
    }

    static final class Recorder implements LineHighlighter {
        final String syntax;
        final List<String> lines = new ArrayList<>();
        int resets;

        Recorder(String syntax) {
            this.syntax = syntax;
        }

        @Override
        public AttributedString highlight(String line) {
            lines.add(line);
            return new AttributedString(line, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
        }

        @Override
        public void reset() {
            resets++;
        }
    }
}
