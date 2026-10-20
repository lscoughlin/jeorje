package org.github.lscoughlin.jeorje.core.compaction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.PruneEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prunes chunks to a level from 0 to 3. The compactor model chooses kept spans and keywords (or a decision note
 * for a {@code CAREFUL} chunk at level 2). Jeorje validates each answer, asks again one time, and then uses the
 * {@link FallbackPruner}. Without a compactor model, only the fallback pruner runs.
 */
public final class Pruner {
    private static final Logger log = LoggerFactory.getLogger(Pruner.class);
    private static final Pattern SKILL_NAME = Pattern.compile(
        "(?m)^\\s*(?:name:|skill:)\\s*([\\w.-]+)|\"name\"\\s*:\\s*\"([\\w.-]+)\"");
    private static final Pattern ERROR_NAME = Pattern.compile("\\b\\w*(?:Exception|Error)\\b");

    /** The settings of the pruner. {@code levels} holds the share to keep at each level. */
    public record Config(List<Double> levels, int batchChunks, int batchTokens, int maxKeywords,
                         JeorjeConfig.Prompt prompt, Path promptBaseDir) {
        public Config {
            levels = List.copyOf(levels);
        }

        public static Config defaults() {
            return new Config(List.of(1.0, 0.5, 0.2, 0.0), 20, 16_000, 12, null, null);
        }
    }

    /**
     * One chunk to prune.
     *
     * @param level the wanted level
     * @param previous the earlier pruning entry of the chunk, or null
     */
    public record Job(Chunk chunk, Protection protection, int level, PruneEntry previous) {}

    private final LlmClient compactor;
    private final ContextStore store;
    private final Config config;
    private final KeywordHints hints;
    private final FallbackPruner fallback;

    /** The compactor can be null. Then the fallback pruner does all the work. The store can be null in tests. */
    public Pruner(LlmClient compactor, ContextStore store, TermStats stats, Config config) {
        this.compactor = compactor;
        this.store = store;
        this.config = config;
        this.hints = new KeywordHints(store, stats, config.maxKeywords());
        this.fallback = new FallbackPruner(stats);
    }

    public Config config() { return config; }

    /** True when a compactor model is set. */
    public boolean hasCompactor() { return compactor != null; }

    /** Prunes one chunk. */
    public PruneResult prune(Job job, Set<String> keepPaths) {
        return pruneBatch(List.of(job), keepPaths).getFirst();
    }

    /** Prunes many chunks. The results are in the order of the jobs. */
    public List<PruneResult> pruneBatch(List<Job> jobs, Set<String> keepPaths) {
        var results = new PruneResult[jobs.size()];
        var groups = new LinkedHashMap<String, List<Integer>>();
        for (var i = 0; i < jobs.size(); i++) {
            var job = jobs.get(i);
            var level = effectiveLevel(job);
            var prevLevel = job.previous() == null ? 0 : job.previous().level();
            var chunk = job.chunk();
            if (level == 0 || level <= prevLevel) {
                results[i] = unchanged(job);
            } else if ("recall".equals(chunk.toolName())) {
                results[i] = stub(job);
            } else if (level >= 3) {
                results[i] = hintOnly(job, keepPaths);
            } else {
                var guard = Spans.human(chunk).isEmpty() ? "" : ":human";
                groups.computeIfAbsent(level + ":" + job.protection() + guard, k -> new ArrayList<>()).add(i);
            }
        }
        for (var indexes : groups.values()) {
            var first = jobs.get(indexes.getFirst());
            var level = effectiveLevel(first);
            for (var batch : batches(indexes, jobs)) {
                runBatch(batch, jobs, level, first.protection(), keepPaths, results);
            }
        }
        return List.of(results);
    }

    private int effectiveLevel(Job job) {
        var prev = job.previous() == null ? 0 : job.previous().level();
        if (job.protection() == Protection.PROTECTED) return 0;
        var max = job.protection().maxLevel();
        // A chunk with words of the user stops at level 2 and keeps those words.
        if (max > 2 && !Spans.human(job.chunk()).isEmpty()) max = 2;
        return Math.max(prev, Math.min(job.level(), max));
    }

    private List<List<Integer>> batches(List<Integer> indexes, List<Job> jobs) {
        var out = new ArrayList<List<Integer>>();
        var cur = new ArrayList<Integer>();
        var tokens = 0;
        for (var i : indexes) {
            var t = Tokens.estimate(jobs.get(i).chunk().text());
            if (!cur.isEmpty() && (cur.size() >= config.batchChunks() || tokens + t > config.batchTokens())) {
                out.add(cur);
                cur = new ArrayList<>();
                tokens = 0;
            }
            cur.add(i);
            tokens += t;
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private void runBatch(List<Integer> batch, List<Job> jobs, int level, Protection prot, Set<String> keepPaths,
                          PruneResult[] results) {
        var target = config.levels().get(level);
        var human = !Spans.human(jobs.get(batch.getFirst()).chunk()).isEmpty();
        var noteMode = prot == Protection.CAREFUL && level == 2 && !human;
        var valid = new HashMap<Integer, CompactorAnswer>();
        var pending = new ArrayList<>(batch);
        var feedback = new HashMap<String, String>();
        if (compactor != null) {
            var system = CompactorPrompt.build(config.prompt(), config.promptBaseDir(), level, target, prot,
                config.maxKeywords());
            var call = new CompactorCall(compactor);
            for (var attempt = 0; attempt < 2 && !pending.isEmpty(); attempt++) {
                var chunks = pending.stream().map(i -> jobs.get(i).chunk()).toList();
                Map<String, CompactorAnswer> answers;
                try {
                    answers = call.call(system, chunks, level, feedback);
                } catch (RuntimeException e) {
                    var reason = String.valueOf(e.getMessage());
                    log.atWarn().log(() -> "compactor call failed: " + reason);
                    pending.forEach(i -> feedback.put(jobs.get(i).chunk().chunkId(), "no valid answer: " + reason));
                    continue;
                }
                var still = new ArrayList<Integer>();
                for (var i : pending) {
                    var chunk = jobs.get(i).chunk();
                    var a = answers.get(chunk.chunkId());
                    var problem = a == null ? java.util.Optional.of("no result for this chunk")
                        : a.validate(chunk, target, noteMode);
                    if (problem.isEmpty()) {
                        valid.put(i, a);
                    } else {
                        feedback.put(chunk.chunkId(), problem.get());
                        still.add(i);
                    }
                }
                pending = still;
            }
        }
        for (var i : batch) {
            var job = jobs.get(i);
            var answer = valid.get(i);
            results[i] = answer != null ? fromModel(job, level, answer, keepPaths)
                : fromFallback(job, level, target, keepPaths);
        }
    }

    private PruneResult fromModel(Job job, int level, CompactorAnswer a, Set<String> keepPaths) {
        var chunk = job.chunk();
        var text = chunk.text();
        var keywords = keywords(job, a.keywords());
        if (a.note() != null && job.protection() == Protection.CAREFUL && level == 2 && Spans.human(chunk).isEmpty()) {
            var note = new LinkedHashMap<>(a.note());
            var files = new LinkedHashSet<>(note.getOrDefault("files", List.of()));
            keepPaths.stream().filter(text::contains).forEach(files::add);
            if (!files.isEmpty()) note.put("files", List.copyOf(files));
            var rendered = CompactorAnswer.renderNote(note);
            return new PruneResult(chunk.chunkId(), job.protection(), level, List.of(), rendered, keywords,
                PruneResult.Source.MODEL, rendered.length(), text.length());
        }
        var spans = Spans.union(Spans.union(a.spans(), Spans.human(chunk)), Spans.occurrences(text, keepPaths));
        return new PruneResult(chunk.chunkId(), job.protection(), level, spans, null, keywords,
            PruneResult.Source.MODEL, Spans.size(spans), text.length());
    }

    private PruneResult fromFallback(Job job, int level, double target, Set<String> keepPaths) {
        var chunk = job.chunk();
        var spans = Spans.union(Spans.union(fallback.prune(chunk.text(), target, keepPaths), Spans.human(chunk)),
            Spans.occurrences(chunk.text(), keepPaths));
        return new PruneResult(chunk.chunkId(), job.protection(), level, spans, null, keywords(job, List.of()),
            PruneResult.Source.FALLBACK, Spans.size(spans), chunk.text().length());
    }

    private PruneResult hintOnly(Job job, Set<String> keepPaths) {
        var chunk = job.chunk();
        var keywords = keywords(job, List.of());
        var topics = topics(chunk);
        var kept = keepPaths.stream().filter(chunk.text()::contains).sorted().toList();
        if (!kept.isEmpty()) topics += (topics.isEmpty() ? "" : "; ") + "files " + String.join(", ", kept);
        return new PruneResult(chunk.chunkId(), job.protection(), 3, List.of(), topics, keywords,
            compactor != null ? PruneResult.Source.MODEL : PruneResult.Source.FALLBACK, 0, chunk.text().length());
    }

    private PruneResult stub(Job job) {
        var chunk = job.chunk();
        return new PruneResult(chunk.chunkId(), job.protection(), 3, List.of(), PrunedChunks.stub(chunk), List.of(),
            PruneResult.Source.STUB, 0, chunk.text().length());
    }

    private PruneResult unchanged(Job job) {
        var p = job.previous();
        var chunk = job.chunk();
        if (p == null || p.level() == 0) {
            return new PruneResult(chunk.chunkId(), job.protection(), 0, List.of(), null, List.of(),
                PruneResult.Source.NONE, chunk.text().length(), chunk.text().length());
        }
        var kept = p.keptSpans().isEmpty() && p.note() != null ? p.note().length() : Spans.size(p.keptSpans());
        return new PruneResult(chunk.chunkId(), job.protection(), p.level(), p.keptSpans(), p.note(), p.keywords(),
            PruneResult.Source.NONE, p.level() >= 3 ? 0 : kept, chunk.text().length());
    }

    /** The keywords of a result: earlier keywords first, then the proposed ones, then the best candidates. */
    private List<String> keywords(Job job, List<String> proposed) {
        var all = new ArrayList<String>();
        if (job.previous() != null) all.addAll(job.previous().keywords());
        all.addAll(proposed);
        var skill = skillKeyword(job.chunk());
        all.removeIf(k -> k.equals(skill));
        var hint = hints.build(job.chunk(), all);
        var out = new ArrayList<>(hint.keywords());
        if (skill != null) out.add(skill);
        return out;
    }

    private static String skillKeyword(Chunk chunk) {
        if (!"skill".equals(chunk.toolName())) return null;
        var m = SKILL_NAME.matcher(chunk.text());
        if (!m.find()) return null;
        return "skill:" + (m.group(1) != null ? m.group(1) : m.group(2));
    }

    /** The topics line of a level 3 chunk, built from words of the chunk. */
    static String topics(Chunk chunk) {
        var sb = new StringBuilder();
        if (chunk.toolName() != null) sb.append(chunk.toolName());
        var path = Tokens.PATH.matcher(chunk.text());
        if (path.find()) {
            var p = path.group();
            sb.append(sb.isEmpty() ? "" : " ").append(p.substring(p.lastIndexOf('/') + 1));
        }
        var err = ERROR_NAME.matcher(chunk.text());
        if (err.find()) sb.append(sb.isEmpty() ? "" : "; ").append("found ").append(err.group());
        return sb.toString();
    }
}
