package org.github.lscoughlin.jeorje.core.compaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compaction policy. After each turn, {@link #compact} applies the soft trigger (the working context is more
 * than 50% of the budget) and the hard trigger (the next request can use more than 80%). It prunes in the
 * eviction order and sends the chunks to the {@link Pruner} in batches. It never changes {@code PROTECTED}
 * chunks or recent turns. The index keeps the original text.
 */
public final class ContextCompactor {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);
    private static final int LARGE_RESULT_TOKENS = 2_000;

    /**
     * The result of one compaction run.
     *
     * @param event the {@code Compacted} event, or null when nothing changed
     */
    public record Outcome(List<ContextEntry> entries, int chunksPruned, int tokensFreed, boolean soft, boolean hard,
                          AgentEvent.Compacted event) {}

    private final Pruner pruner;
    private final CompactionSettings settings;
    private final AgentId agent;

    public ContextCompactor(Pruner pruner, CompactionSettings settings, AgentId agent) {
        this.pruner = pruner;
        this.settings = settings;
        this.agent = agent;
    }

    /**
     * @param entries the pruned chunks and recent chunks of the working context, oldest first
     * @param currentTurn the number of the turn that just ended
     * @param overheadTokens the tokens of the system prompt, the pinned items, and the recent turns
     * @param keepPaths the immediate file paths that pruning keeps at every level
     */
    public Outcome compact(List<ContextEntry> entries, int currentTurn, int overheadTokens, Set<String> keepPaths) {
        return compact(entries, currentTurn, overheadTokens, keepPaths, Set.of());
    }

    /** The policy values. */
    public CompactionSettings settings() { return settings; }

    /**
     * Compacts the working context. The triggers compare the tokens of all entries plus the overhead with the
     * budget. The overhead is everything else in the next request: the system prompt, the tool definitions, the
     * pinned items, and the recent turns.
     *
     * @param overheadTokens the tokens of the working context that are not in the entries
     * @param concluded ids of search results that a later edit, write, or answer used. They go to level 3 at
     *                  the first compaction that touches them, and they are pruned first.
     */
    public Outcome compact(List<ContextEntry> entries, int currentTurn, int overheadTokens, Set<String> keepPaths,
                           Set<String> concluded) {
        var work = new ArrayList<>(entries);
        var before = total(work);
        var soft = before + overheadTokens > settings.softLimit();
        var hard = before + overheadTokens > settings.hardLimit();
        var pruned = new java.util.HashSet<String>();
        if (soft) {
            var order = evictionOrder(work, currentTurn, concluded);
            var targets = new java.util.HashMap<String, Integer>();
            for (var i : order) targets.put(work.get(i).chunk().chunkId(), softTarget(work.get(i), currentTurn, concluded));
            apply(work, order, targets, keepPaths, pruned, -1);
        }
        if (hard) {
            while (total(work) + overheadTokens > settings.softLimit()) {
                var order = evictionOrder(work, currentTurn, concluded);
                if (order.isEmpty()) break;
                var targets = new java.util.HashMap<String, Integer>();
                for (var i : order) targets.put(work.get(i).chunk().chunkId(), work.get(i).level() + 1);
                var progressed = apply(work, order, targets, keepPaths, pruned, settings.softLimit() - overheadTokens);
                if (!progressed) break;
            }
        }
        var after = total(work);
        var freed = Math.max(0, before - after);
        var event = pruned.isEmpty() ? null : new AgentEvent.Compacted(agent, pruned.size(), freed);
        log.atDebug().log(() -> "compaction pruned " + pruned.size() + " chunks and freed " + freed + " tokens");
        return new Outcome(List.copyOf(work), pruned.size(), freed, soft, hard, event);
    }

    /**
     * Prunes the chunks in batches. A {@code stopBelow} of -1 means no early stop. Returns true when at least
     * one chunk changed.
     */
    private boolean apply(List<ContextEntry> work, List<Integer> order, java.util.Map<String, Integer> targets,
                          Set<String> keepPaths, Set<String> pruned, long stopBelow) {
        var progressed = false;
        var i = 0;
        while (i < order.size()) {
            var jobs = new ArrayList<Pruner.Job>();
            var idx = new ArrayList<Integer>();
            var tokens = 0;
            while (i < order.size() && jobs.size() < settings.batchChunks()) {
                var e = work.get(order.get(i));
                var t = Tokens.estimate(e.chunk().text());
                if (!jobs.isEmpty() && tokens + t > settings.batchTokens()) break;
                jobs.add(new Pruner.Job(e.chunk(), e.protection(), targets.get(e.chunk().chunkId()), e.prune()));
                idx.add(order.get(i));
                tokens += t;
                i++;
            }
            var results = pruner.pruneBatch(jobs, keepPaths);
            for (var k = 0; k < results.size(); k++) {
                var old = work.get(idx.get(k));
                var updated = old.with(results.get(k));
                if (updated.level() > old.level()) {
                    work.set(idx.get(k), updated);
                    pruned.add(old.chunk().chunkId());
                    progressed = true;
                }
            }
            if (stopBelow >= 0 && total(work) <= stopBelow) break;
        }
        return progressed;
    }

    private int softTarget(ContextEntry e, int currentTurn, Set<String> concluded) {
        return switch (e.protection()) {
            case AGGRESSIVE -> concluded.contains(e.chunk().chunkId()) ? 3 : currentTurn - e.chunk().turn() >= settings.recentTurns() + settings.aggressiveDropAfterTurns()
                ? 3 : Math.max(2, e.level());
            case CAREFUL -> Math.min(2, e.level() + 1);
            case PROTECTED -> 0;
        };
    }

    /** The indexes of the eligible entries in the eviction order. */
    List<Integer> evictionOrder(List<ContextEntry> work, int currentTurn) {
        return evictionOrder(work, currentTurn, Set.of());
    }

    /** The eligible entries in the eviction order. Concluded searches come first. */
    List<Integer> evictionOrder(List<ContextEntry> work, int currentTurn, Set<String> concluded) {
        var idx = new ArrayList<Integer>();
        for (var i = 0; i < work.size(); i++) {
            var e = work.get(i);
            var old = e.chunk().turn() <= currentTurn - settings.recentTurns();
            if (e.protection() != Protection.PROTECTED && old && e.level() < e.protection().maxLevel()) idx.add(i);
        }
        ToIntFunction<Integer> rank = i -> concluded.contains(work.get(i).chunk().chunkId()) ? -1 : rank(work.get(i));
        idx.sort(Comparator.comparingInt(rank)
            .thenComparing((Integer i) -> rank.applyAsInt(i) <= 0 ? -Tokens.estimate(work.get(i).chunk().text()) : 0)
            .thenComparingInt(i -> work.get(i).chunk().turn())
            .thenComparingInt(i -> work.get(i).chunk().part()));
        return idx;
    }

    /** 0: large tool results, 1: raw logs, 2: other tool output, 3: CAREFUL chunks. */
    static int rank(ContextEntry e) {
        if (e.protection() == Protection.CAREFUL) return 3;
        var c = e.chunk();
        if (!"tool_result".equals(c.kind())) return 2;
        if (Tokens.estimate(c.text()) >= LARGE_RESULT_TOKENS) return 0;
        if ("bash".equals(c.toolName()) || c.text().contains("BUILD ") || c.text().contains("Tests run:")) return 1;
        return 2;
    }

    private static int total(List<ContextEntry> entries) {
        return entries.stream().mapToInt(ContextEntry::tokens).sum();
    }
}
