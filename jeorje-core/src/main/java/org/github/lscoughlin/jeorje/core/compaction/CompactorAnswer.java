package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.Span;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer of the compactor for one chunk, with the parser and the validation rules.
 *
 * @param note the decision note bullets by type, or null for a span answer
 */
record CompactorAnswer(String id, List<Span> spans, List<String> keywords, Map<String, List<String>> note) {
    /** The bullet types of a decision note. */
    static final List<String> NOTE_TYPES = List.of("decision", "reason", "rejected", "open question", "files");
    private static final int MAX_BULLET_CHARS = 240;
    private static final int SKIP_RATIO_BELOW = 40;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Parses the text of a model answer. Returns the answers by chunk id. Throws when the JSON is not valid. */
    static Map<String, CompactorAnswer> parse(String raw) {
        var start = raw.indexOf('{');
        var end = raw.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("no JSON object in the answer");
        var root = MAPPER.readTree(raw.substring(start, end + 1));
        var results = root.get("results");
        if (results == null || !results.isArray()) throw new IllegalArgumentException("no results array");
        var out = new LinkedHashMap<String, CompactorAnswer>();
        for (var r : results) {
            var id = r.get("id");
            if (id == null || !id.isString()) continue;
            out.put(id.asString(), fromNode(id.asString(), r));
        }
        return out;
    }

    private static CompactorAnswer fromNode(String id, JsonNode r) {
        var spans = new ArrayList<Span>();
        var s = r.get("spans");
        if (s != null && s.isArray()) {
            for (var pair : s) {
                if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isNumber() || !pair.get(1).isNumber()) {
                    throw new IllegalArgumentException("bad span in answer for " + id);
                }
                spans.add(new Span(pair.get(0).asInt(), pair.get(1).asInt()));
            }
        }
        var keywords = new ArrayList<String>();
        var k = r.get("keywords");
        if (k != null && k.isArray()) for (var x : k) if (x.isString()) keywords.add(x.asString());
        Map<String, List<String>> note = null;
        var n = r.get("note");
        if (n != null && n.isObject()) {
            note = new LinkedHashMap<>();
            for (var type : NOTE_TYPES) {
                var arr = n.get(type);
                if (arr == null) continue;
                var list = new ArrayList<String>();
                if (arr.isArray()) for (var x : arr) list.add(x.asString());
                else list.add(arr.asString());
                note.put(type, list);
            }
        }
        return new CompactorAnswer(id, spans, keywords, note);
    }

    /** Renders a decision note as bullets. */
    static String renderNote(Map<String, List<String>> note) {
        var sb = new StringBuilder();
        for (var type : NOTE_TYPES) {
            var items = note.get(type);
            if (items == null || items.isEmpty()) continue;
            if (type.equals("files")) {
                sb.append("- files: ").append(String.join(", ", items)).append('\n');
            } else {
                for (var item : items) sb.append("- ").append(type).append(": ").append(item).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Checks the answer against the rules. Spans must be inside the chunk and must not overlap. The kept share
     * must be from 50% to 150% of the target. Each keyword must appear in the chunk. A note must only name
     * keywords and files from the chunk and must be shorter than the chunk.
     *
     * @return the reason for a failure, or empty when the answer is valid
     */
    Optional<String> validate(Chunk chunk, double target, boolean noteMode) {
        var text = chunk.text();
        for (var k : keywords) {
            if (!text.contains(k)) return Optional.of("keyword is not in the chunk: " + k);
        }
        if (noteMode) {
            if (note == null) return Optional.of("a decision note is needed");
            var rendered = renderNote(note);
            if (rendered.isBlank()) return Optional.of("the decision note is empty");
            for (var f : note.getOrDefault("files", List.of())) {
                if (!text.contains(f)) return Optional.of("file is not in the chunk: " + f);
            }
            for (var e : note.entrySet()) {
                for (var b : e.getValue()) {
                    if (b.length() > MAX_BULLET_CHARS) return Optional.of("a note bullet is too long");
                }
            }
            if (rendered.length() >= text.length()) return Optional.of("the note is not shorter than the chunk");
            return Optional.empty();
        }
        var bad = Spans.check(spans, text);
        if (bad.isPresent()) return bad;
        if (spans.isEmpty()) return Optional.of("no spans");
        if (text.length() >= SKIP_RATIO_BELOW) {
            var ratio = (double) Spans.size(Spans.normalize(spans)) / text.length();
            if (ratio < target * 0.5 || ratio > target * 1.5) {
                return Optional.of("kept share " + Math.round(ratio * 100) + "% is outside the range for the target");
            }
        }
        return Optional.empty();
    }
}
