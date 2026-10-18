package org.github.lscoughlin.jeorje.ui.editor.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.github.lscoughlin.jeorje.ui.chat.LanguageResolver;
import org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type;
import org.github.lscoughlin.jeorje.ui.syntax.HighlighterFactory;
import org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter;
import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Colors a Markdown file. The Markdown syntax colors the text outside the blocks, and the syntax of each
 * language colors the content of its fenced blocks.
 *
 * <p>The highlighter keeps one {@link LineHighlighter} for each language. It calls {@code reset()} at the start
 * of each block.
 */
public final class MarkdownHighlighter implements InjectionRule {

    private static final Logger log = LoggerFactory.getLogger(MarkdownHighlighter.class);

    /** The color of fence lines. */
    static final AttributedStyle MARKER = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
    /** The color of a language word that Jeorje recognized. */
    static final AttributedStyle LANGUAGE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
    /** The dim color of an unknown language word and of the content of a block with no language. */
    static final AttributedStyle DIM = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK | AttributedStyle.BRIGHT);

    private final SyntaxInfo hostSyntax;
    private final LanguageResolver resolver;
    private final HighlighterFactory factory;
    private final InjectionOptions options;

    private final LineHighlighter base;
    private final Map<String, Optional<LineHighlighter>> byName = new HashMap<>();
    private final Map<String, Optional<Language>> byTag = new HashMap<>();
    private List<String> plainLines = new ArrayList<>();
    private MarkdownRegions regions;

    private record Language(SyntaxInfo syntax, LineHighlighter highlighter) {
    }

    /** The mutable context of one pass over the lines. */
    private static final class Pass {
        LineHighlighter highlighter;
        boolean unknown;
        boolean longBlock;
        boolean inBlock;
    }

    public MarkdownHighlighter(SyntaxInfo hostSyntax, LanguageResolver resolver, HighlighterFactory factory,
                               InjectionOptions options) {
        this.hostSyntax = hostSyntax;
        this.resolver = resolver;
        this.factory = factory;
        this.options = options;
        this.base = create(hostSyntax).orElse(null);
    }

    @Override
    public boolean appliesTo(SyntaxInfo host) {
        return options.enabled() && host != null && host.name().equalsIgnoreCase(hostSyntax.name());
    }

    @Override
    public void load(List<String> lines) {
        if (options.enabled()) {
            regions = MarkdownRegions.of(lines);
            plainLines = new ArrayList<>();
        } else {
            plainLines = new ArrayList<>(lines);
        }
    }

    @Override
    public void replace(int first, int removed, List<String> inserted) {
        if (options.enabled()) {
            if (regions == null) {
                regions = MarkdownRegions.of(List.of());
            }
            regions.replace(first, removed, inserted);
        } else {
            plainLines.subList(first, first + removed).clear();
            plainLines.addAll(first, inserted);
        }
    }

    @Override
    public int lineCount() {
        return options.enabled() ? (regions == null ? 0 : regions.size()) : plainLines.size();
    }

    /** The region map; null when the layer is off or no text is loaded. */
    public MarkdownRegions regions() {
        return regions;
    }

    @Override
    public List<AttributedString> lines(int first, int last) {
        first = Math.max(0, first);
        last = Math.min(last, lineCount() - 1);
        var out = new ArrayList<AttributedString>();
        if (last < first) {
            return out;
        }
        if (options.noColor()) {
            for (var i = first; i <= last; i++) {
                out.add(new AttributedString(text(i)));
            }
            return out;
        }
        var start = Math.max(0, first - options.replayLines());
        if (base != null) {
            base.reset();
        }
        if (!options.enabled()) {
            for (var i = start; i <= last; i++) {
                var styled = highlight(base, text(i));
                if (i >= first) {
                    out.add(styled);
                }
            }
            return out;
        }
        var pass = new Pass();
        for (var i = start; i <= last; i++) {
            var styled = styleLine(i, start, first, pass);
            if (i >= first && styled != null) {
                out.add(styled);
            }
        }
        return out;
    }

    private String text(int index) {
        return options.enabled() ? regions.line(index) : plainLines.get(index);
    }

    private AttributedString styleLine(int i, int start, int first, Pass pass) {
        var line = regions.line(i);
        switch (regions.type(i)) {
            case TEXT -> {
                pass.inBlock = false;
                return highlight(base, line);
            }
            case FRONT_MATTER -> {
                pass.inBlock = false;
                var yaml = language("yaml").map(Language::highlighter).orElse(null);
                if (yaml != null && (i == start || regions.type(i - 1) != Type.FRONT_MATTER)) {
                    yaml.reset();
                }
                return highlight(yaml, line);
            }
            case FENCE_OPEN -> {
                var fence = regions.fence(i);
                var lang = language(fence.info());
                enterBlock(pass, lang.orElse(null), regions.blockEnd(i) - i + 1, true);
                return openingLine(line, fence, lang.isPresent());
            }
            case FENCE_CLOSE -> {
                if (!pass.inBlock) {
                    enterBlock(pass, language(regions.fence(i).info()).orElse(null),
                            regions.blockEnd(i) - regions.blockStart(i) + 1, false);
                }
                pass.inBlock = false;
                return new AttributedString(line, MARKER);
            }
            default -> {
                if (!pass.inBlock) {
                    var open = regions.blockStart(i);
                    enterBlock(pass, language(regions.fence(i).info()).orElse(null),
                            regions.blockEnd(i) - open + 1, false);
                }
                return contentLine(i, first, pass);
            }
        }
    }

    private void enterBlock(Pass pass, Language lang, int blockLines, boolean fromOpen) {
        pass.inBlock = true;
        pass.unknown = lang == null;
        pass.highlighter = lang == null ? null : lang.highlighter();
        pass.longBlock = blockLines > options.maxBlockLines();
        if (pass.highlighter != null && (fromOpen || !pass.longBlock)) {
            pass.highlighter.reset();
        }
    }

    private AttributedString contentLine(int i, int first, Pass pass) {
        var line = regions.line(i);
        if (pass.longBlock && pass.highlighter != null) {
            if (i < first) {
                return null;
            }
            if (i == first) {
                pass.highlighter.reset();
            }
        }
        var column = regions.fence(i).column();
        var cut = 0;
        while (cut < column && cut < line.length() && (line.charAt(cut) == ' ' || line.charAt(cut) == '\t'
                || line.charAt(cut) == '>')) {
            cut++;
        }
        var rest = line.substring(cut);
        var body = pass.highlighter == null ? new AttributedString(rest, DIM) : highlight(pass.highlighter, rest);
        if (cut == 0) {
            return body;
        }
        return new AttributedStringBuilder().append(line.substring(0, cut)).append(body).toAttributedString();
    }

    private AttributedString openingLine(String line, MarkdownRegions.Fence fence, boolean known) {
        var markerEnd = Math.min(line.length(), fence.column() + fence.length());
        var sb = new AttributedStringBuilder();
        sb.append(line.substring(0, markerEnd), MARKER);
        var rest = line.substring(markerEnd);
        var tag = LanguageResolver.tagOf(fence.info());
        var at = tag.isEmpty() ? -1 : rest.toLowerCase(java.util.Locale.ROOT).indexOf(tag);
        if (at < 0) {
            sb.append(rest, MARKER);
        } else {
            sb.append(rest.substring(0, at), MARKER);
            sb.append(rest.substring(at, at + tag.length()), known ? LANGUAGE : DIM);
            sb.append(rest.substring(at + tag.length()), MARKER);
        }
        return sb.toAttributedString();
    }

    private AttributedString highlight(LineHighlighter highlighter, String line) {
        if (highlighter == null) {
            return new AttributedString(line);
        }
        var max = options.maxLineChars();
        var head = line.length() > max ? line.substring(0, max) : line;
        try {
            var styled = highlighter.highlight(head);
            if (line.length() > max) {
                return new AttributedStringBuilder().append(styled).append(line.substring(max))
                        .toAttributedString();
            }
            return styled;
        } catch (RuntimeException e) {
            log.atWarn().log(() -> "A syntax rule failed in a Markdown block: " + e);
            return new AttributedString(line);
        }
    }

    // ---- languages ----

    private Optional<Language> language(String info) {
        var tag = LanguageResolver.tagOf(info);
        if (tag.isEmpty()) {
            return Optional.empty();
        }
        return byTag.computeIfAbsent(tag, t -> resolver.resolve(t).flatMap(syntax -> highlighterFor(syntax)
                .map(h -> new Language(syntax, h))));
    }

    private Optional<LineHighlighter> highlighterFor(SyntaxInfo syntax) {
        return byName.computeIfAbsent(syntax.name(), n -> create(syntax));
    }

    private Optional<LineHighlighter> create(SyntaxInfo syntax) {
        try {
            return Optional.of(factory.create(syntax));
        } catch (Exception | LinkageError e) {
            log.atWarn().log(() -> "Cannot load the syntax " + syntax.name() + ": " + e);
            return Optional.empty();
        }
    }
}
