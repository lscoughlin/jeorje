package org.github.lscoughlin.jeorje.core.compaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.HistoryQuery;
import org.github.lscoughlin.jeorje.core.port.Hit;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers: a term-count store, a scripted model, and chunk builders. */
final class CompactionTestSupport {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CompactionTestSupport() {}

    static Chunk chunk(String agent, int turn, int part, String kind, String tool, String text) {
        return new Chunk(agent + ":" + turn + "." + part, "s1", agent, turn, part, Instant.EPOCH, kind, tool, text);
    }

    static Chunk chunk(int turn, String kind, String tool, String text) {
        return chunk("a", turn, 0, kind, tool, text);
    }

    /** A text of many lines with a unique identifier. */
    static String body(String id, int lines) {
        var sb = new StringBuilder();
        for (var i = 0; i < lines; i++) {
            sb.append("the service ").append(id).append(" is processing the request and it will then write the output")
                .append(" for step ").append((char) ('a' + i % 26)).append(" without any problem\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    /** A store that counts terms. Recall chunks are skipped. */
    static final class ListStore implements ContextStore {
        final List<Chunk> chunks = new ArrayList<>();

        @Override public void append(List<Chunk> c) { chunks.addAll(c); }

        @Override public List<Hit> search(HistoryQuery q) {
            var terms = Arrays.asList(q.text().toLowerCase().replace("\"", "").split("\\W+"));
            return chunks.stream()
                .filter(c -> !"recall".equals(c.toolName()))
                .map(c -> new Hit(c, terms.stream().mapToInt(t -> count(c.text().toLowerCase(), t)).sum(), ""))
                .filter(h -> h.score() > 0)
                .sorted(Comparator.comparingDouble((Hit h) -> -h.score()).thenComparing(h -> h.chunk().chunkId()))
                .limit(q.limit()).toList();
        }

        private static int count(String text, String term) {
            var n = 0;
            for (var i = text.indexOf(term); i >= 0 && !term.isEmpty(); i = text.indexOf(term, i + 1)) n++;
            return n;
        }

        @Override public Optional<Chunk> get(String id) {
            return chunks.stream().filter(c -> c.chunkId().equals(id)).findFirst();
        }

        @Override public List<Chunk> window(String id, int b, int a) { return List.of(); }

        @Override public void close() {}
    }

    /** A model that answers with a function of the request. It counts the calls. */
    static final class ScriptedLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<LlmRequest> requests = new ArrayList<>();
        private final Function<LlmRequest, String> responder;

        ScriptedLlm(Function<LlmRequest, String> responder) { this.responder = responder; }

        @Override public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
            calls.incrementAndGet();
            requests.add(request);
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
                try {
                    var text = responder.apply(request);
                    subscriber.onNext(new LlmEvent.TextDelta(text));
                    subscriber.onNext(new LlmEvent.MessageStop("end"));
                    subscriber.onComplete();
                } catch (RuntimeException e) {
                    subscriber.onError(e);
                }
            };
        }
    }

    /** The chunk ids and texts in the user message of a compactor request. */
    static List<String[]> requested(LlmRequest request) {
        var out = new ArrayList<String[]>();
        var text = ((org.github.lscoughlin.jeorje.core.model.Text) request.messages().getFirst().content().getFirst()).text();
        for (var c : MAPPER.readTree(text).get("chunks")) out.add(new String[] {c.get("id").asString(), c.get("text").asString()});
        return out;
    }

    /** An answer that keeps the first {@code share} of each chunk and names the given keyword. */
    static String keepFirst(LlmRequest request, double share, String keywordPrefix) {
        var sb = new StringBuilder("{\"results\":[");
        var first = true;
        for (var c : requested(request)) {
            if (!first) sb.append(',');
            first = false;
            var end = (int) (c[1].length() * share);
            var m = java.util.regex.Pattern.compile(keywordPrefix + "\\w*").matcher(c[1]);
            var kw = m.find() ? m.group() : "step";
            sb.append("{\"id\":\"").append(c[0]).append("\",\"spans\":[[0,").append(end).append("]],\"keywords\":[\"")
                .append(kw).append("\"]}");
        }
        return sb.append("]}").toString();
    }
}
