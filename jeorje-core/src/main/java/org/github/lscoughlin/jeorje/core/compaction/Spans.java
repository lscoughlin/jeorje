package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.Span;

/** Operations on kept spans: validation, normalization, union, and the pruned text builder. */
public final class Spans {
    /** The text between two kept spans that are not neighbors in the original. */
    public static final String GAP = " ... ";

    private Spans() {}

    /**
     * Checks spans against a chunk text. Each span must be inside the text, must not be empty, and must not
     * overlap another span.
     *
     * @return the reason for a failure, or empty when the spans are valid
     */
    public static Optional<String> check(List<Span> spans, String text) {
        var sorted = spans.stream().sorted(Comparator.comparingInt(Span::start)).toList();
        var prevEnd = 0;
        for (var s : sorted) {
            if (s.start() < 0 || s.end() > text.length()) return Optional.of("span " + s + " is outside the chunk");
            if (s.end() <= s.start()) return Optional.of("span " + s + " is empty");
            if (s.start() < prevEnd) return Optional.of("span " + s + " overlaps another span");
            prevEnd = s.end();
        }
        return Optional.empty();
    }

    /** Returns the spans in order. Spans that touch are joined. The input must be valid. */
    public static List<Span> normalize(List<Span> spans) {
        var out = new ArrayList<Span>();
        for (var s : spans.stream().sorted(Comparator.comparingInt(Span::start)).toList()) {
            if (!out.isEmpty() && out.getLast().end() >= s.start()) {
                var last = out.removeLast();
                out.add(new Span(last.start(), Math.max(last.end(), s.end())));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** The union of two span lists. Overlaps are allowed in the input. */
    public static List<Span> union(List<Span> a, List<Span> b) {
        var all = new ArrayList<>(a);
        all.addAll(b);
        return normalize(all);
    }

    /** The number of characters that the spans keep. */
    public static int size(List<Span> spans) {
        return spans.stream().mapToInt(s -> s.end() - s.start()).sum();
    }

    /** Finds the occurrences of each given string in the text and returns them as spans. */
    public static List<Span> occurrences(String text, java.util.Collection<String> needles) {
        var out = new ArrayList<Span>();
        for (var n : needles) {
            if (n.isEmpty()) continue;
            for (var i = text.indexOf(n); i >= 0; i = text.indexOf(n, i + n.length())) out.add(new Span(i, i + n.length()));
        }
        return normalize(out);
    }

    /**
     * Builds the pruned text. Kept spans in order are joined by the original white space (reduced to one
     * space or one line break) when only white space lies between them, and by {@link #GAP} otherwise.
     */
    public static String build(String text, List<Span> spans) {
        var sb = new StringBuilder();
        Span prev = null;
        for (var s : normalize(spans)) {
            if (prev != null) {
                var between = text.substring(prev.end(), s.start());
                if (between.isBlank()) sb.append(between.indexOf('\n') >= 0 ? "\n" : " ");
                else sb.append(GAP);
            }
            sb.append(text, s.start(), s.end());
            prev = s;
        }
        return sb.toString();
    }
}
