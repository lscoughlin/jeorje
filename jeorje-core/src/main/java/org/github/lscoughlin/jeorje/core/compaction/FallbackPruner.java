package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.Span;

/**
 * Prunes without a model. It removes blank lines, repeated lines, English stop words, and then the lines with
 * the lowest score, until the text reaches the target. A line score is the sum of the inverse document
 * frequency of its terms. Lines with a path, an error word, or a number stay.
 */
public final class FallbackPruner {
    private record Tok(int start, int end, int line, boolean forced) {}

    private final TermStats stats;

    public FallbackPruner(TermStats stats) { this.stats = stats; }

    /**
     * @param target the share of the characters to keep, from 0 to 1
     * @param keepPaths paths whose tokens always stay
     * @return the kept spans in order
     */
    public List<Span> prune(String text, double target, Collection<String> keepPaths) {
        var goal = (int) Math.ceil(text.length() * target);
        var keepRanges = Spans.occurrences(text, keepPaths);
        var toks = new ArrayList<Tok>();
        var lineForced = new ArrayList<Boolean>();
        var lineTexts = new ArrayList<String>();
        var lineStart = 0;
        var lineNo = 0;
        while (lineStart <= text.length()) {
            var nl = text.indexOf('\n', lineStart);
            var lineEnd = nl < 0 ? text.length() : nl;
            var line = text.substring(lineStart, lineEnd);
            var forcedLine = false;
            var m = Tokens.WORD.matcher(line);
            while (m.find()) {
                var s = lineStart + m.start();
                var e = lineStart + m.end();
                var forced = isForced(m.group()) || covered(keepRanges, s, e);
                forcedLine |= forced;
                toks.add(new Tok(s, e, lineNo, forced));
            }
            lineForced.add(forcedLine);
            lineTexts.add(line);
            lineNo++;
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        var alive = new boolean[toks.size()];
        java.util.Arrays.fill(alive, true);
        // Stage 1 (blank lines) needs no work: a blank line has no token.
        // Stage 2: repeated lines.
        var seen = new HashSet<String>();
        for (var i = 0; i < lineTexts.size(); i++) {
            var t = lineTexts.get(i).strip();
            if (!t.isEmpty() && !seen.add(t)) {
                final var ln = i;
                for (var k = 0; k < toks.size(); k++) if (toks.get(k).line() == ln) alive[k] = false;
            }
        }
        // Stage 3: stop words.
        for (var k = 0; k < toks.size() && size(toks, alive) > goal; k++) {
            var t = toks.get(k);
            if (alive[k] && !t.forced() && Tokens.STOP_WORDS.contains(text.substring(t.start(), t.end()).toLowerCase())) {
                alive[k] = false;
            }
        }
        // Stage 4: lines with the lowest score.
        if (size(toks, alive) > goal) {
            var lines = new ArrayList<Integer>();
            for (var i = 0; i < lineTexts.size(); i++) if (!lineForced.get(i)) lines.add(i);
            lines.sort(Comparator.comparingDouble((Integer i) -> score(lineTexts.get(i))).thenComparing(i -> i));
            for (var ln : lines) {
                if (size(toks, alive) <= goal) break;
                for (var k = 0; k < toks.size(); k++) if (toks.get(k).line() == ln) alive[k] = false;
            }
        }
        var spans = new ArrayList<Span>();
        Tok first = null;
        Tok last = null;
        for (var k = 0; k < toks.size(); k++) {
            if (alive[k]) {
                var t = toks.get(k);
                if (first != null && last.line() == t.line() && k > 0 && alive[k - 1]) {
                    last = t;
                } else {
                    if (first != null) spans.add(new Span(first.start(), last.end()));
                    first = t;
                    last = t;
                }
            }
        }
        if (first != null) spans.add(new Span(first.start(), last.end()));
        return spans;
    }

    private double score(String line) {
        var sum = 0.0;
        var m = Tokens.WORD.matcher(line);
        while (m.find()) sum += stats.idf(m.group());
        return sum;
    }

    private static boolean isForced(String token) {
        return Tokens.PATH.matcher(token).find() || Tokens.NUMBER.matcher(token).find()
            || Tokens.ERROR_WORD.matcher(token).find();
    }

    private static boolean covered(List<Span> ranges, int s, int e) {
        for (var r : ranges) if (s < r.end() && e > r.start()) return true;
        return false;
    }

    private static int size(List<Tok> toks, boolean[] alive) {
        var n = 0;
        for (var k = 0; k < toks.size(); k++) if (alive[k]) n += toks.get(k).end() - toks.get(k).start() + 1;
        return n;
    }
}
