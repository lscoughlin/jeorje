package org.github.lscoughlin.jeorje.core.implement;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanFilesTest {
    @TempDir Path tmp;

    private static final String PLAN = """
        ---
        title: A plan
        status: draft            # draft, approved, in-progress, done, or blocked
        goal: A goal.
        ---

        # Plan: A plan

        ## Story S1: One
        - **Goal:** Do it.
        - **Depends on:** none
        - **Touches:** `a.txt`

        ### Tasks
        - [ ] S1.T1 Do it.
        """;

    @Test
    void writesTheStatusAndKeepsTheComment() throws IOException {
        var file = tmp.resolve("p.md");
        Files.writeString(file, PLAN);
        PlanFiles.writeStatus(file, "in-progress");
        var text = Files.readString(file);
        assertTrue(text.contains("status: in-progress            # draft, approved"), text);
        assertEquals("in-progress", PlanParser.parse(file).status());
        assertEquals(PLAN.replace("status: draft", "status: in-progress"), text);
    }

    @Test
    void addsAMissingStatusLine() throws IOException {
        var file = tmp.resolve("p.md");
        Files.writeString(file, PLAN.replace("status: draft            # draft, approved, in-progress, done, or blocked\n", ""));
        PlanFiles.writeStatus(file, "approved");
        assertEquals("approved", PlanParser.parse(file).status());
    }

    @Test
    void thePlanObjectGetsTheStatusToo() throws IOException {
        var plan = PlanParser.parse(PLAN);
        assertEquals("blocked", PlanFiles.withStatus(plan, "blocked").status());
        assertEquals("draft", plan.status());
    }

    @Test
    void makesAShellCommandFromTheVerificationPart() {
        assertEquals("mvn -pl a test", PlanFiles.command("`mvn -pl a test`"));
        assertEquals("mvn -q test", PlanFiles.command("```\nmvn -q test\n```"));
        assertEquals("a && b", PlanFiles.command("- `a`\n- `b`"));
        assertEquals("", PlanFiles.command(null));
        assertEquals("", PlanFiles.command("  \n"));
    }

    @Test
    void findsPlansByReferenceAndNewestOpenPlan() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("plan"));
        var older = dir.resolve("older.md");
        var newer = dir.resolve("newer.md");
        var finished = dir.resolve("finished.md");
        Files.writeString(older, PLAN);
        Files.writeString(newer, PLAN);
        Files.writeString(finished, PLAN.replace("- [ ]", "- [x]"));
        Files.writeString(dir.resolve("broken.md"), "no front matter");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(5_000));
        Files.setLastModifiedTime(finished, java.nio.file.attribute.FileTime.fromMillis(9_000));
        assertEquals(List.of(newer, older), PlanFiles.openPlans(dir));
        assertEquals(newer, PlanFiles.find(dir, null).orElseThrow());
        assertEquals(older, PlanFiles.find(dir, "older").orElseThrow());
        assertEquals(older, PlanFiles.find(dir, "older.md").orElseThrow());
        assertEquals(older, PlanFiles.find(dir, older.toString()).orElseThrow());
        assertTrue(PlanFiles.find(dir, "missing").isEmpty());
        assertEquals("older", PlanFiles.slug(older));
    }
}
