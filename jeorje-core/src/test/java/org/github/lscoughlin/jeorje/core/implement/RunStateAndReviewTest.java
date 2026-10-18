package org.github.lscoughlin.jeorje.core.implement;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStateAndReviewTest {
    @TempDir Path tmp;

    @Test
    void theFileStoreKeepsTheRunState() {
        var store = RunStateStore.file(tmp.resolve("state").resolve("implement.json"));
        assertTrue(store.load().isEmpty());
        var state = new RunState("plan/demo.md", "running", List.of(
            new RunState.Entry("S1", "done", 1, 0, "sub-1", "jeorje/demo/s1", "/w", "made it", "", List.of(), ""),
            new RunState.Entry("S2", "blocked", 2, 2, "sub-2", "b", "/w2", "", "conflict", List.of("a.txt"), "note")));
        store.save(state);
        var loaded = RunStateStore.file(tmp.resolve("state").resolve("implement.json")).load().orElseThrow();
        assertEquals(state, loaded);
        assertEquals("blocked", loaded.entry("S2").state());
        assertNull(loaded.entry("S9"));
    }

    @Test
    void theManifestStoreKeepsTheRunStateInTheManifest() {
        var file = tmp.resolve("session").resolve("manifest.json");
        var created = org.github.lscoughlin.jeorje.core.session.SessionManifest.create("s1",
            java.time.Instant.parse("2026-01-01T00:00:00Z"), null);
        created.write(file);
        var store = RunStateStore.manifest(file);
        assertTrue(store.load().isEmpty());
        var state = new RunState("plan/demo.md", "paused", List.of(
            new RunState.Entry("S1", "done", 1, 0, "sub-1", "b", "/w", "ok", "", List.of("a.txt"), "")));
        store.save(state);
        var again = RunStateStore.manifest(file).load().orElseThrow();
        assertEquals(state, again);
        var manifest = org.github.lscoughlin.jeorje.core.session.SessionManifest.read(file);
        assertEquals("s1", manifest.sessionId());
        assertEquals("idle", manifest.runState());
        assertEquals("paused", manifest.implementState().path("status").asString());
        assertFalse(java.nio.file.Files.exists(tmp.resolve("session").resolve("implement.json")));
    }

    @Test
    void aBrokenFileGivesNoState() throws Exception {
        var file = tmp.resolve("bad.json");
        java.nio.file.Files.writeString(file, "{not json");
        assertTrue(RunStateStore.file(file).load().isEmpty());
    }

    @Test
    void theAdvisorAnswerIsApproveOrAListOfProblems() {
        assertTrue(AdvisorReviewer.parse("approve").approved());
        assertTrue(AdvisorReviewer.parse("  Approve.\n").approved());
        var rejected = AdvisorReviewer.parse("- The test for refill is missing.\n- No thread safety.");
        assertFalse(rejected.approved());
        assertEquals(List.of("The test for refill is missing.", "No thread safety."), rejected.problems());
        assertFalse(AdvisorReviewer.parse("approve\n- but one thing").approved());
        assertFalse(AdvisorReviewer.parse("").approved());
    }

    @Test
    void theReportListsStoriesAndBlockedReasons() {
        var report = new ImplementReport("plan/demo.md", "blocked", List.of(
            new ImplementReport.StoryResult("S1", "One", StoryState.DONE, 1, "made it", List.of("a.txt"), ""),
            new ImplementReport.StoryResult("S2", "Two", StoryState.BLOCKED, 2, "", List.of(), "no API")),
            "failed", "boom", null);
        var text = report.render();
        assertTrue(text.contains("S1 One: done"));
        assertTrue(text.contains("files: a.txt"));
        assertTrue(text.contains("S2 Two: blocked (attempts 2)"));
        assertTrue(text.contains("reason: no API"));
        assertTrue(text.contains("Plan verification: failed"));
        assertTrue(text.contains("boom"));
        assertEquals(1, report.blockedCount());
    }
}
