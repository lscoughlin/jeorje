package org.github.lscoughlin.jeorje.ui.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.github.lscoughlin.jeorje.ui.syntax.SyntaxInfo;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Renders tool results for the chat view: {@code edit}, {@code read}, {@code grep}, and diff-like output of
 * {@code bash} and {@code webfetch}. Every other result stays plain text.
 *
 * <p>The result of a block is a cached list of styled lines. A resize wraps the cached lines again with
 * {@link #wrap}. The lines keep their styles.
 */
public final class ResultRenderers {

    private static final Pattern READ_LINE = Pattern.compile("^(\\s*\\d+)\\t(.*)$");
    private static final Pattern GREP_LINE = Pattern.compile("^(.+?):(\\d+):(.*)$");
    private static final AttributedStyle DIM = AttributedStyle.DEFAULT.faint();
    private static final int CACHE_SIZE = 256;

    private final HighlightContext context;
    private final Map<String, List<AttributedString>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<AttributedString>> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public ResultRenderers(HighlightContext context) {
        this.context = context;
    }

    /**
     * Renders the result of a tool call. The result is cached for the key.
     *
     * @param key    the id of the block, for example the tool use id
     * @param tool   the tool name
     * @param path   the file of the tool input, or null
     * @param output the text of the result
     */
    public List<AttributedString> render(String key, String tool, String path, String output) {
        var cacheKey = key + '|' + tool + '|' + path + '|' + output.length() + '|' + output.hashCode();
        synchronized (cache) {
            var hit = cache.get(cacheKey);
            if (hit != null) {
                return hit;
            }
        }
        var lines = List.copyOf(renderUncached(tool, path, output));
        synchronized (cache) {
            cache.put(cacheKey, lines);
        }
        return lines;
    }

    /** The number of cached blocks. */
    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Wraps cached lines to a width. The styles stay. */
    public static List<AttributedString> wrap(List<AttributedString> lines, int width) {
        var out = new ArrayList<AttributedString>();
        for (var line : lines) {
            if (width <= 0 || line.columnLength() <= width) {
                out.add(line);
            } else {
                out.addAll(line.columnSplitLength(width));
            }
        }
        return out;
    }

    private List<AttributedString> renderUncached(String tool, String path, String output) {
        var lines = split(output);
        var config = context.config();
        if (!config.enabled()) {
            return plain(lines);
        }
        return switch (tool) {
            case "edit" -> config.diffRendering() ? limited(lines, new DiffRenderer(context, path)) : plain(lines);
            case "read" -> config.read() ? readResult(lines, path) : plain(lines);
            case "grep" -> config.read() ? grepResult(lines) : plain(lines);
            case "bash", "webfetch" -> config.diffRendering() && DiffRenderer.looksLikeDiff(output)
                    ? limited(lines, new DiffRenderer(context, null)) : plain(lines);
            default -> plain(lines);
        };
    }

    private List<AttributedString> limited(List<String> lines, DiffRenderer diff) {
        var max = context.config().maxLines();
        var out = new ArrayList<AttributedString>();
        for (var i = 0; i < lines.size(); i++) {
            if (i == max) {
                out.add(limitNote());
                out.addAll(plain(lines.subList(i, lines.size())));
                return out;
            }
            out.add(diff.line(lines.get(i)));
        }
        return out;
    }

    private List<AttributedString> readResult(List<String> lines, String path) {
        var syntax = context.resolver().forFile(path).orElse(null);
        var highlighter = context.open(syntax);
        var max = context.config().maxLines();
        var out = new ArrayList<AttributedString>();
        var count = 0;
        var noted = false;
        for (var raw : lines) {
            var m = READ_LINE.matcher(raw);
            if (!m.matches()) {
                out.add(new AttributedString(HighlightContext.display(raw)));
                continue;
            }
            if (count == max && highlighter != null && !noted) {
                out.add(limitNote());
                noted = true;
            }
            var sb = new AttributedStringBuilder();
            sb.append(m.group(1) + "\t", DIM);
            sb.append(count >= max ? new AttributedString(HighlightContext.display(m.group(2)))
                    : context.highlight(syntax, highlighter, m.group(2)));
            out.add(sb.toAttributedString());
            count++;
        }
        return out;
    }

    private List<AttributedString> grepResult(List<String> lines) {
        var out = new ArrayList<AttributedString>();
        var max = context.config().maxLines();
        var count = 0;
        var noted = false;
        var highlighters = new java.util.HashMap<String, org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter>();
        for (var raw : lines) {
            var m = GREP_LINE.matcher(raw);
            if (!m.matches()) {
                out.add(new AttributedString(HighlightContext.display(raw)));
                continue;
            }
            var syntax = context.resolver().forFile(m.group(1)).orElse(null);
            if (count >= max && !noted) {
                out.add(limitNote());
                noted = true;
            }
            var prefix = m.group(1) + ":" + m.group(2) + ":";
            var sb = new AttributedStringBuilder();
            sb.append(prefix, context.config().noColor() ? DIM : DIM.foreground(AttributedStyle.CYAN));
            if (syntax == null || count >= max) {
                sb.append(HighlightContext.display(m.group(3)));
            } else {
                var highlighter = highlighters.computeIfAbsent(syntax.name(), k -> {
                    var opened = context.open(syntax);
                    return opened == null ? NONE : opened;
                });
                if (highlighter == NONE) {
                    sb.append(HighlightContext.display(m.group(3)));
                } else {
                    // Grep lines come from different places in a file. Each line starts with a fresh state.
                    highlighter.reset();
                    sb.append(context.highlight(syntax, highlighter, m.group(3)));
                }
            }
            out.add(sb.toAttributedString());
            count++;
        }
        return out;
    }

    private static final org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter NONE =
            new org.github.lscoughlin.jeorje.ui.syntax.LineHighlighter() {
                @Override
                public AttributedString highlight(String line) {
                    return new AttributedString(line);
                }

                @Override
                public void reset() {
                }
            };

    private AttributedString limitNote() {
        return new AttributedString("[Colors stop after " + context.config().maxLines() + " lines.]", DIM);
    }

    private static List<AttributedString> plain(List<String> lines) {
        var out = new ArrayList<AttributedString>(lines.size());
        for (var line : lines) {
            out.add(new AttributedString(HighlightContext.display(line)));
        }
        return out;
    }

    private static List<String> split(String output) {
        var lines = new ArrayList<>(List.of(output.split("\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
