package org.github.lscoughlin.jeorje.core.compaction;

import java.util.List;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.PruneEntry;

/** Renders the working-context form of a pruned chunk: a header, the kept text, and the keyword hint. */
public final class PrunedChunks {
    private static final Pattern CHUNK_ID = Pattern.compile("[A-Za-z0-9_-]+(?:[.-][A-Za-z0-9_-]+)*:\\d+\\.\\d+");

    private PrunedChunks() {}

    /** Renders a chunk with its pruning entry. Level 0 renders the original text. */
    public static String render(Chunk chunk, PruneEntry entry) {
        if (entry == null || entry.level() == 0) return chunk.text();
        if ("recall".equals(chunk.toolName())) {
            return "[" + chunk.chunkId() + " recall result | stub]\n" + stub(chunk);
        }
        var original = Math.max(1, chunk.text().length());
        var body = body(chunk, entry);
        var kept = entry.level() >= 3 ? 0 : entry.keptSpans().isEmpty() && entry.note() != null
            ? entry.note().length() : Spans.size(entry.keptSpans());
        var pct = kept * 100 / original;
        var header = "[" + chunk.chunkId() + " " + chunk.kind()
            + (chunk.toolName() == null ? "" : " " + chunk.toolName())
            + " | pruned L" + entry.level() + " (" + pct + "% kept)]";
        return header + "\n" + (body.isEmpty() ? "" : body + "\n") + hint(chunk.chunkId(), entry.keywords());
    }

    private static String body(Chunk chunk, PruneEntry entry) {
        if (entry.level() >= 3) return entry.note() == null ? "" : entry.note();
        if (entry.keptSpans().isEmpty() && entry.note() != null) return entry.note();
        return Spans.build(chunk.text(), entry.keptSpans());
    }

    /** The {@code recall:} line. */
    public static String hint(String chunkId, List<String> keywords) {
        var parts = new java.util.ArrayList<String>();
        for (var k : keywords) parts.add(k.matches(".*[\\s,].*") ? "\"" + k + "\"" : k);
        parts.add("id=" + chunkId);
        return "[recall: " + String.join(", ", parts) + "]";
    }

    /** The ids of the source chunks of a recall reference chunk. */
    public static List<String> sourceIds(Chunk recall) {
        var text = recall.text();
        var i = text.indexOf("returned=");
        var scope = i >= 0 ? text.substring(i + "returned=".length()) : text;
        var ids = new java.util.LinkedHashSet<String>();
        var m = CHUNK_ID.matcher(scope);
        while (m.find()) ids.add(m.group());
        return List.copyOf(ids);
    }

    /** The stub text for an evicted recall result. */
    public static String stub(Chunk recall) {
        return "sources: " + String.join(", ", sourceIds(recall));
    }
}
