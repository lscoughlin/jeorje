package org.github.lscoughlin.jeorje.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanParserTest {

    @Test
    void parsesFrontMatterStoriesAndParts() {
        var plan = PlanParser.parse(PlanFixtures.SAMPLE);
        assertEquals("Add rate limiting", plan.title());
        assertEquals("draft", plan.status());
        assertEquals("mvn verify", plan.verification());
        assertEquals(2, plan.stories().size());
        var s1 = plan.stories().get(0);
        assertEquals("S1", s1.id());
        assertEquals("Token bucket", s1.title());
        assertEquals("A bucket.", s1.goal());
        assertEquals(List.of(), s1.dependsOn());
        assertEquals(List.of("src/a/", "src/b.java"), s1.touches());
        assertEquals("default", s1.model());
        assertTrue(s1.context().contains("### not a heading"));
        assertEquals("- It works.", s1.acceptance());
        assertEquals("`mvn test`", s1.verification());
        assertEquals(3, s1.tasks().size());
        assertEquals(TaskState.OPEN, s1.tasks().get(0).state());
        assertEquals(TaskState.DONE, s1.tasks().get(1).state());
        assertEquals("Add tests with a continuation.", s1.tasks().get(1).text());
        assertEquals(TaskState.BLOCKED, s1.tasks().get(2).state());
        var s2 = plan.stories().get(1);
        assertEquals(List.of("S1"), s2.dependsOn());
        assertNull(s2.model());
        assertNull(s2.verification());
        assertNull(s2.handoff());
    }

    @Test
    void parsesHandoffAndPreviousAttempt() {
        var text = PlanFixtures.SAMPLE + "\n### Handoff\nS1 added `Bucket`.\n\n### Previous attempt\nIt failed.\n";
        var s2 = PlanParser.parse(text).stories().get(1);
        assertEquals("S1 added `Bucket`.", s2.handoff());
        assertEquals("It failed.", s2.previousAttempt());
    }

    @Test
    void roundTripsSample() {
        var plan = PlanParser.parse(PlanFixtures.SAMPLE);
        var again = PlanParser.parse(PlanWriter.render(plan));
        assertEquals(plan.stories().size(), again.stories().size());
        for (var i = 0; i < plan.stories().size(); i++) {
            var a = plan.stories().get(i);
            var b = again.stories().get(i);
            assertEquals(a.withTasks(a.tasks().stream().map(t -> new Task(t.id(), t.text(), t.state(), -1)).toList())
                            .goal(), b.goal());
            assertEquals(a.context(), b.context());
            assertEquals(a.acceptance(), b.acceptance());
            assertEquals(a.verification(), b.verification());
            assertEquals(a.touches(), b.touches());
            assertEquals(a.dependsOn(), b.dependsOn());
            assertEquals(a.tasks().stream().map(Task::render).toList(), b.tasks().stream().map(Task::render).toList());
        }
        assertEquals(plan.front(), again.front());
        assertEquals(plan.preamble(), again.preamble());
    }

    @ParameterizedTest
    @ValueSource(strings = {"01-foundation-and-agent.md", "02-interface-and-orchestration.md"})
    void roundTripsRealPlanFilesExactly(String name) throws IOException {
        var file = Path.of("..", "plan", name);
        Assumptions.assumeTrue(Files.exists(file), "plan file is missing");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        var plan = PlanParser.parse(text);
        assertEquals(Integer.parseInt(plan.front().get("stories")), plan.stories().size());
        assertEquals(text, PlanWriter.render(plan));
        assertEquals(text, PlanWriter.update(text, plan));
        plan.stories().forEach(s -> assertTrue(!s.tasks().isEmpty() && !s.goal().isBlank(), s.id()));
    }

    @Test
    void errorsHaveLineNumbers() {
        var noFront = assertThrows(PlanException.class, () -> PlanParser.parse("# Plan\n"));
        assertEquals(1, noFront.line());

        var unclosed = assertThrows(PlanException.class, () -> PlanParser.parse("---\ntitle: x\n"));
        assertTrue(unclosed.getMessage().contains("not closed"));

        var badHeading = assertThrows(PlanException.class,
                () -> PlanParser.parse("---\ntitle: x\n---\n\n## Story : no id\n"));
        assertEquals(5, badHeading.line());

        var badLabel = assertThrows(PlanException.class,
                () -> PlanParser.parse("---\ntitle: x\n---\n## Story S1: T\n- **Color:** red\n"));
        assertEquals(5, badLabel.line());

        var badSection = assertThrows(PlanException.class,
                () -> PlanParser.parse("---\ntitle: x\n---\n## Story S1: T\n- **Goal:** g\n\n### Notes\nx\n"));
        assertEquals(7, badSection.line());

        var badTask = assertThrows(PlanException.class,
                () -> PlanParser.parse("---\ntitle: x\n---\n## Story S1: T\n### Tasks\n- [?] S1.T1 x\n"));
        assertEquals(6, badTask.line());

        var plain = assertThrows(PlanException.class,
                () -> PlanParser.parse("---\ntitle: x\n---\n## Story S1: T\n### Tasks\njust text\n"));
        assertEquals(6, plain.line());

        var dup = assertThrows(PlanException.class,
                () -> PlanParser.parse("---\ntitle: x\n---\n## Story S1: T\n## Story S1: U\n"));
        assertEquals(5, dup.line());

        var epilogue = PlanParser.parse("---\ntitle: x\n---\n## Story S1: T\n## Other\ntext\n");
        assertEquals("## Other\ntext", epilogue.epilogue());
        assertEquals(1, epilogue.stories().size());
    }

    @Test
    void frontMatterLimitIsTwentyLines() {
        var ok = new StringBuilder("---\n");
        for (var i = 0; i < 20; i++) ok.append("k").append(i).append(": v\n");
        ok.append("---\n");
        assertEquals(20, PlanParser.parse(ok.toString()).front().size());
        var tooLong = ok.toString().replaceFirst("k19: v\n", "k19: v\nk20: v\n");
        var ex = assertThrows(PlanException.class, () -> PlanParser.parse(tooLong));
        assertTrue(ex.getMessage().contains("limit"));
    }

    @Test
    void readsFiles(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        var file = dir.resolve("p.md");
        Files.writeString(file, PlanFixtures.SAMPLE);
        assertEquals(2, PlanParser.parse(file).stories().size());
    }
}
