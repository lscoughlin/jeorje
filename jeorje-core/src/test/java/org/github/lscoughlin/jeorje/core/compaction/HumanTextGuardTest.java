package org.github.lscoughlin.jeorje.core.compaction;

import static org.github.lscoughlin.jeorje.core.compaction.CompactionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.compaction.ContextClassifier.Context;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.model.Turn;
import org.github.lscoughlin.jeorje.core.session.SessionManifest.Span;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The words of the user survive every pruning level, in both chunk formats. */
class HumanTextGuardTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ANSWER = "sqlite, and do not use postgres";
    private static final String USER = "USER: plan the migration without downtime";
    private final AgentId agent = new AgentId("a");

    private static String filler(String word) {
        return ("the " + word + " worker walks around the old garden and sees nothing today\n").repeat(30);
    }

    /** A turn chunk of the legacy format: a user text, filler, an ask_user answer, and more filler. */
    private static Chunk legacyChunk(int turn) {
        var text = USER + "\nASSISTANT: " + filler("quiet") + "TOOL_CALL ask_user {\"question\":\"Which database?\"}\n"
            + "TOOL_RESULT ask_user User answer: " + ANSWER + "\nASSISTANT: " + filler("loud");
        return chunk(turn, "mixed", "ask_user", text.stripTrailing());
    }

    private Turn askTurn(int n) {
        var ask = JSON.readTree("{\"question\":\"Which database?\"}");
        return new Turn(agent, n, List.of(Message.user("plan the migration"),
            new Message(Role.ASSISTANT, List.of(new ToolUse("q" + n, "ask_user", ask))),
            new Message(Role.USER, List.of(new ToolResult("q" + n, "User answer: " + ANSWER, false))),
            Message.assistant(filler("calm"))));
    }

    @Test
    void humanRangesCoverUserTextAndAskUserAnswers() {
        var c = legacyChunk(2);
        var ranges = Spans.human(c);
        assertEquals(2, ranges.size());
        assertEquals(USER, c.text().substring(ranges.get(0).start(), ranges.get(0).end()));
        assertEquals("TOOL_RESULT ask_user User answer: " + ANSWER,
            c.text().substring(ranges.get(1).start(), ranges.get(1).end()));
        assertEquals(List.of(new Span(0, 3)), Spans.human(chunk(1, "user", null, "fix")));
        assertTrue(Spans.human(chunk(1, "tool_result", "read", "TOOL_RESULT read USER: not human")).isEmpty());
    }

    @Test
    void format2ChunksOfUserTextAndAnswersAreProtected() {
        var chunker = new TurnChunker("s1");
        var chunks = new java.util.ArrayList<Chunk>();
        chunks.addAll(chunker.chunk(askTurn(1), Instant.EPOCH));
        chunks.addAll(chunker.chunk(askTurn(2), Instant.EPOCH));
        chunks.addAll(chunker.chunk(askTurn(3), Instant.EPOCH));
        var classes = new ContextClassifier().classify(chunks, Context.of(10));
        for (var c : chunks) {
            var human = c.kind().equals("user");
            assertEquals(human ? Protection.PROTECTED : Protection.CAREFUL, classes.get(c.chunkId()),
                c.chunkId() + " " + c.kind());
        }
        assertTrue(chunks.stream().anyMatch(c -> "ask_user".equals(c.toolName())
            && classes.get(c.chunkId()) == Protection.PROTECTED));
    }

    @Test
    void format1TurnChunkKeepsTheWordsOfTheUserAtEveryLevel() {
        var c = legacyChunk(2);
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        for (var level = 1; level <= 3; level++) {
            var res = pruner.prune(new Pruner.Job(c, Protection.CAREFUL, level, null), Set.of());
            assertEquals(Math.min(level, 2), res.level(), "a chunk with user words stops at level 2");
            assertNull(res.note(), "no decision note for a chunk with user words");
            var text = Spans.build(c.text(), res.keptSpans());
            assertTrue(text.contains(USER), "level " + level + ": " + text);
            assertTrue(text.contains("TOOL_RESULT ask_user User answer: " + ANSWER), "level " + level + ": " + text);
        }
    }

    @Test
    void aMultiLineFormResultStaysWholeInAFormat1Chunk() {
        var form = "TOOL_RESULT ask_user User answers:\n- db \"Which database?\": sqlite\n"
            + "- note \"More?\": do not use postgres\nand keep the tables\n";
        var text = USER + "\nASSISTANT: " + filler("quiet") + "TOOL_CALL ask_user {\"questions\":[]}\n" + form
            + "ASSISTANT: " + filler("loud");
        var c = chunk(2, "mixed", "ask_user", text.stripTrailing());
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        for (var level = 1; level <= 2; level++) {
            var res = pruner.prune(new Pruner.Job(c, Protection.CAREFUL, level, null), Set.of());
            var pruned = Spans.build(c.text(), res.keptSpans());
            assertTrue(pruned.contains(form.stripTrailing()), "level " + level + ": " + pruned);
        }
    }

    @Test
    void anAnswerOfTheCompactorThatDropsTheUserWordsIsRepaired() {
        var c = legacyChunk(2);
        var keep = c.text().length() / 5;
        var llm = new ScriptedLlm(r -> "{\"results\":[{\"id\":\"" + c.chunkId() + "\",\"spans\":[[" + (USER.length() + 20)
            + "," + (USER.length() + 20 + keep) + "]],\"keywords\":[\"Which\"]}]}");
        var pruner = new Pruner(llm, null, TermStats.UNIFORM, Pruner.Config.defaults());
        var res = pruner.prune(new Pruner.Job(c, Protection.CAREFUL, 2, null), Set.of());
        assertEquals(PruneResult.Source.MODEL, res.source());
        var text = Spans.build(c.text(), res.keptSpans());
        assertTrue(text.contains(USER), text);
        assertTrue(text.contains(ANSWER), text);
    }

    @Test
    void theCompactorAsksForSpansNotANoteWhenTheChunkHasUserWords() {
        var c = legacyChunk(2);
        var llm = new ScriptedLlm(r -> "{\"results\":[{\"id\":\"" + c.chunkId()
            + "\",\"note\":{\"decision\":[\"use sqlite\"]},\"keywords\":[]}]}");
        var pruner = new Pruner(llm, null, TermStats.UNIFORM, Pruner.Config.defaults());
        var res = pruner.prune(new Pruner.Job(c, Protection.CAREFUL, 2, null), Set.of());
        assertEquals(PruneResult.Source.FALLBACK, res.source(), "a note is not a valid answer here");
        assertTrue(Spans.build(c.text(), res.keptSpans()).contains(ANSWER));
    }

    @Test
    void theHardTriggerKeepsTheAnswerInAFormat1Chunk() {
        var c = legacyChunk(2);
        var entry = ContextEntry.of(c, Protection.CAREFUL);
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        var compactor = new ContextCompactor(pruner, new CompactionSettings(10, 50, 80, 6, 12, 20, 16_000),
            new AgentId("a"));
        var out = compactor.compact(List.of(entry), 40, Set.of());
        assertTrue(out.hard());
        var rendered = out.entries().getFirst().render();
        assertTrue(rendered.contains(ANSWER), rendered);
        assertTrue(rendered.contains(USER), rendered);
    }
}
