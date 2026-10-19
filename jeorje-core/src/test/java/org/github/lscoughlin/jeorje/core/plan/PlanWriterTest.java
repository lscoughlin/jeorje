package org.github.lscoughlin.jeorje.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanWriterTest {

    @Test
    void updateChangesOnlyCheckboxes() {
        var plan = PlanParser.parse(PlanFixtures.SAMPLE);
        var s1 = plan.stories().get(0).withTaskState("S1.T1", TaskState.DONE).withTaskState("S1.T3", TaskState.OPEN);
        var out = PlanWriter.update(PlanFixtures.SAMPLE, plan.withStory(s1));
        var before = PlanFixtures.SAMPLE.split("\n", -1);
        var after = out.split("\n", -1);
        assertEquals(before.length, after.length);
        var changed = 0;
        for (var i = 0; i < before.length; i++) {
            if (!before[i].equals(after[i])) {
                changed++;
                assertTrue(before[i].startsWith("- ["), before[i]);
            }
        }
        assertEquals(2, changed);
        assertTrue(out.contains("- [x] S1.T1 Create the class."));
        assertTrue(out.contains("- [ ] S1.T3 Blocked task."));
    }

    @Test
    void updateAddsReplacesAndRemovesHandoff() {
        var plan = PlanParser.parse(PlanFixtures.SAMPLE);
        var withHandoff = plan.withStory(plan.stories().get(0).withHandoff("S0 changed `A.java`."));
        var added = PlanWriter.update(PlanFixtures.SAMPLE, withHandoff);
        var parsed = PlanParser.parse(added);
        assertEquals("S0 changed `A.java`.", parsed.stories().get(0).handoff());
        assertNull(parsed.stories().get(1).handoff());
        assertEquals(PlanFixtures.SAMPLE.length() + "\n### Handoff\nS0 changed `A.java`.\n".length(), added.length());

        var replaced = PlanWriter.update(added, plan.withStory(plan.stories().get(0).withHandoff("New text.\nSecond line.")));
        assertEquals("New text.\nSecond line.", PlanParser.parse(replaced).stories().get(0).handoff());

        var removed = PlanWriter.update(replaced, plan.withStory(plan.stories().get(0).withHandoff(null)));
        assertEquals(PlanFixtures.SAMPLE, removed);
    }

    @Test
    void updateIgnoresUnknownStoriesAndKeepsOtherText() {
        var plan = PlanFixtures.plan(PlanFixtures.story("Z9", java.util.List.of(), java.util.List.of()));
        assertEquals(PlanFixtures.SAMPLE, PlanWriter.update(PlanFixtures.SAMPLE, plan));
    }

    @Test
    void updateKeepsWindowsLineEndings() {
        var crlf = PlanFixtures.SAMPLE.replace("\n", "\r\n");
        var plan = PlanParser.parse(crlf);
        var s2 = plan.stories().get(1).withTaskState("S2.T1", TaskState.DONE);
        var out = PlanWriter.update(crlf, plan.withStory(s2));
        assertTrue(out.contains("- [x] S2.T1 Write it.\r\n"));
        assertEquals(crlf.length(), out.length());
    }

    @Test
    void renderedNewPlanParsesBack() {
        var a = PlanFixtures.story("S1", java.util.List.of(), java.util.List.of("a/"));
        var b = PlanFixtures.story("S2", java.util.List.of("S1"), java.util.List.of("a/b"))
                .withHandoff("Handoff text").withPreviousAttempt("Failed once.");
        var plan = PlanFixtures.plan(a, b);
        var parsed = PlanParser.parse(PlanWriter.render(plan));
        assertEquals("Sample plan", parsed.title());
        assertEquals(2, parsed.stories().size());
        assertEquals("Handoff text", parsed.stories().get(1).handoff());
        assertEquals("Failed once.", parsed.stories().get(1).previousAttempt());
        assertEquals(PlanWriter.render(plan), PlanWriter.render(parsed));
    }

    @Test
    void updateFileWritesOnlyOnChange(@TempDir Path dir) throws IOException {
        var file = dir.resolve("p.md");
        Files.writeString(file, PlanFixtures.SAMPLE);
        var plan = PlanParser.parse(file);
        assertFalse(PlanWriter.updateFile(file, plan));
        var done = plan.withStory(plan.stories().get(1).withTaskState("S2.T1", TaskState.DONE));
        assertTrue(PlanWriter.updateFile(file, done));
        assertTrue(Files.readString(file).contains("- [x] S2.T1"));
        assertTrue(Arrays.stream(dir.toFile().list()).noneMatch(n -> n.endsWith(".tmp")));
        assertTrue(PlanParser.parse(file).stories().get(1).done());
    }
}
