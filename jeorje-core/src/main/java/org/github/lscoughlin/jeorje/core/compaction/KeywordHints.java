package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.Filters;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Syntax;

/**
 * Builds keyword hints. A hint has 3 to 12 keywords. Each keyword is an exact string of the chunk text and is
 * distinctive in the session. The hint is checked as a TEXT query: the chunk must be in the top 3 results.
 */
public final class KeywordHints {
    /** The least number of keywords in a hint. */
    public static final int MIN_KEYWORDS = 3;
    /** A keyword that matches more than this number of other chunks is not distinctive. */
    public static final int MAX_OTHER_MATCHES = 20;

    private static final Pattern TERM = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_$]*(?:[./-][A-Za-z0-9_$]+)*|\\d+(?:\\.\\d+)*");
    private static final Pattern CAMEL = Pattern.compile("[a-z][A-Z]|_");
    private static final int CANDIDATE_LIMIT = 40;

    /** A hint and the result of its self-check. */
    public record Hint(List<String> keywords, boolean verified) {}

    private final ContextStore store;
    private final TermStats stats;
    private final int maxKeywords;

    /** The store can be null. Then the hint has no distinctiveness test and no self-check. */
    public KeywordHints(ContextStore store, TermStats stats, int maxKeywords) {
        this.store = store;
        this.stats = stats;
        this.maxKeywords = Math.max(MIN_KEYWORDS, Math.min(12, maxKeywords));
    }

    /**
     * Builds the hint. The proposed keywords come first (for example, the earlier keywords of the chunk and the
     * answer of the compactor). A proposed keyword that is not in the text is ignored.
     */
    public Hint build(Chunk chunk, List<String> proposed) {
        var text = chunk.text();
        var keywords = new LinkedHashSet<String>();
        for (var k : proposed) {
            if (keywords.size() >= maxKeywords) break;
            if (!k.isBlank() && text.contains(k) && distinctive(k, chunk)) keywords.add(k);
        }
        var pool = new ArrayList<>(candidates(text, stats));
        pool.removeIf(keywords::contains);
        var it = pool.iterator();
        while (keywords.size() < MIN_KEYWORDS && it.hasNext()) {
            var k = it.next();
            if (distinctive(k, chunk)) keywords.add(k);
        }
        var verified = check(chunk, keywords);
        while (!verified && keywords.size() < maxKeywords && it.hasNext()) {
            var k = it.next();
            if (!distinctive(k, chunk)) continue;
            keywords.add(k);
            verified = check(chunk, keywords);
        }
        return new Hint(List.copyOf(keywords), verified);
    }

    /** Runs the keywords as a TEXT query. Returns true when the chunk is in the top 3. */
    public boolean check(Chunk chunk, java.util.Collection<String> keywords) {
        if (store == null || keywords.isEmpty()) return false;
        var hits = store.search(new HistoryQuery(query(keywords), Syntax.TEXT, Filters.none(), 3));
        return hits.stream().limit(3).anyMatch(h -> h.chunk().chunkId().equals(chunk.chunkId()));
    }

    private boolean distinctive(String keyword, Chunk self) {
        if (store == null) return true;
        var hits = store.search(new HistoryQuery(quote(keyword), Syntax.TEXT, Filters.none(), MAX_OTHER_MATCHES + 2));
        var others = hits.stream().map(h -> h.chunk())
            .filter(c -> !c.chunkId().equals(self.chunkId()) && !"recall".equals(c.toolName())).count();
        return others <= MAX_OTHER_MATCHES;
    }

    /** The TEXT query of a keyword list. */
    public static String query(java.util.Collection<String> keywords) {
        return String.join(" ", keywords.stream().map(KeywordHints::quote).toList());
    }

    static String quote(String keyword) {
        return keyword.chars().anyMatch(Character::isWhitespace) ? "\"" + keyword + "\"" : keyword;
    }

    /** Ranks the terms of the text: identifiers, paths, error names, numbers, and rare words first. */
    public static List<String> candidates(String text, TermStats stats) {
        var score = new LinkedHashMap<String, Double>();
        var count = new LinkedHashMap<String, Integer>();
        var m = TERM.matcher(text);
        while (m.find()) {
            var t = m.group();
            if (t.length() < 3 || Tokens.STOP_WORDS.contains(t.toLowerCase())) continue;
            count.merge(t, 1, Integer::sum);
        }
        for (var e : count.entrySet()) {
            var t = e.getKey();
            var bonus = 1.0;
            if (t.contains("/") || t.matches(".*\\.[A-Za-z]{1,5}")) bonus += 1.0;
            if (CAMEL.matcher(t).find() || Character.isUpperCase(t.charAt(0))) bonus += 0.5;
            if (t.endsWith("Exception") || t.endsWith("Error")) bonus += 1.0;
            if (Character.isDigit(t.charAt(0))) bonus += 0.3;
            score.put(t, stats.idf(t) * bonus * (1 + Math.log(e.getValue())));
        }
        return score.entrySet().stream()
            .sorted(Comparator.comparingDouble((java.util.Map.Entry<String, Double> e) -> -e.getValue())
                .thenComparingInt(e -> text.indexOf(e.getKey())))
            .limit(CANDIDATE_LIMIT).map(java.util.Map.Entry::getKey).toList();
    }
}
