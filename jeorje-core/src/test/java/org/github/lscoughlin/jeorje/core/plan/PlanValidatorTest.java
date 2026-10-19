package org.github.lscoughlin.jeorje.core.plan;

import static org.github.lscoughlin.jeorje.core.plan.PlanFixtures.plan;
import static org.github.lscoughlin.jeorje.core.plan.PlanFixtures.story;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.github.lscoughlin.jeorje.core.plan.PlanValidator.Violation;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {
    static final List<String> NONE = List.of();

    static List<Violation> rule(List<Violation> all, String rule) {
        return all.stream().filter(v -> v.rule().equals(rule)).toList();
    }

    @Test
    void validPlanPasses() {
        var plan = plan(story("S1", NONE, List.of("a/")), story("S2", List.of("S1"), List.of("a/b")),
                story("S3", NONE, List.of("c/")));
        assertEquals(List.of(), PlanValidator.validate(plan));
    }

    @Test
    void realPlansAreValidStructurally() throws Exception {
        var file = java.nio.file.Path.of("..", "plan", "02-interface-and-orchestration.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file));
        var plan = PlanParser.parse(file);
        assertEquals(List.of(), rule(PlanValidator.validate(plan), PlanValidator.UNIQUE_IDS));
        assertEquals(List.of(), rule(PlanValidator.validate(plan), PlanValidator.DEPENDS_ON));
    }

    @Test
    void storyIdsMustBeUnique() {
        var all = PlanValidator.validate(plan(story("S1", NONE, NONE), story("S1", NONE, NONE)));
        assertEquals(1, rule(all, PlanValidator.UNIQUE_IDS).stream().filter(v -> v.message().contains("story id")).count());
        assertTrue(rule(all, PlanValidator.UNIQUE_IDS).stream().anyMatch(v -> v.message().contains("Task id S1.T1")));
    }

    @Test
    void taskIdsMustStartWithStoryId() {
        var s = story("S1", NONE, NONE).withTasks(List.of(new Task("S10.T1", "x"), new Task("T2", "y")));
        var all = rule(PlanValidator.validate(plan(s)), PlanValidator.UNIQUE_IDS);
        assertEquals(2, all.size());
        assertTrue(all.get(0).toString().contains("Story S1"));
    }

    @Test
    void requiredPartsAreChecked() {
        var bare = new Story("S1", "", "", NONE, NONE, null, "", "", null, List.<Task>of());
        var all = rule(PlanValidator.validate(plan(bare)), PlanValidator.REQUIRED_PARTS);
        assertEquals(5, all.size());
        assertEquals(List.of(), rule(PlanValidator.validate(plan(story("S1", NONE, NONE))), PlanValidator.REQUIRED_PARTS));
        var empty = rule(PlanValidator.validate(new Plan(java.util.Map.of(), NONE, "", List.<Story>of(), "")), PlanValidator.REQUIRED_PARTS);
        assertEquals(2, empty.size());
    }

    @Test
    void dependsOnMustPointToStories() {
        var all = rule(PlanValidator.validate(plan(story("S1", List.of("S9"), NONE))), PlanValidator.DEPENDS_ON);
        assertEquals(1, all.size());
        assertTrue(all.get(0).message().contains("S9"));
    }

    @Test
    void dependenciesMustNotHaveCycles() {
        var cyc = plan(story("S1", List.of("S3"), NONE), story("S2", List.of("S1"), NONE), story("S3", List.of("S2"), NONE));
        var all = rule(PlanValidator.validate(cyc), PlanValidator.DEPENDS_ON);
        assertEquals(1, all.size());
        assertTrue(all.get(0).message().contains("cycle"));
        var self = rule(PlanValidator.validate(plan(story("S1", List.of("S1"), NONE))), PlanValidator.DEPENDS_ON);
        assertEquals(1, self.size());
        var diamond = plan(story("S1", NONE, NONE), story("S2", List.of("S1"), NONE), story("S3", List.of("S1"), NONE),
                story("S4", List.of("S2", "S3"), NONE));
        assertEquals(List.of(), rule(PlanValidator.validate(diamond), PlanValidator.DEPENDS_ON));
    }

    @Test
    void storiesMustBeSelfContained() {
        var talk = new Story("S1", "T", "g", NONE, NONE, null, "As discussed, use the helper.", "- ok", null,
                List.of(new Task("S1.T1", "Do it.")));
        assertEquals(1, rule(PlanValidator.validate(plan(talk)), PlanValidator.SELF_CONTAINED).size());
        var chat = new Story("S1", "T", "g", NONE, NONE, null, "Use the helper from the conversation earlier.", "- ok", null,
                List.of(new Task("S1.T1", "Do it.")));
        // "from the conversation" is a reference to the chat
        assertEquals(1, rule(PlanValidator.validate(plan(chat)), PlanValidator.SELF_CONTAINED).size());
        var ref = new Story("S2", "T", "g", List.of("S1"), NONE, null, "Context.", "- Works like S1.", null,
                List.of(new Task("S2.T1", "See S1 for details.")));
        var all = rule(PlanValidator.validate(plan(story("S1", NONE, NONE), ref)), PlanValidator.SELF_CONTAINED);
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(v -> v.storyId().equals("S2")));
    }

    @Test
    void handoffAndDependsOnMayNameOtherStories() {
        var s2 = new Story("S2", "T", "g", List.of("S1"), NONE, null, "Context.", "- ok", null,
                List.of(new Task("S2.T1", "Do it."))).withHandoff("S1 changed A.java.");
        assertEquals(List.of(), PlanValidator.validate(plan(story("S1", NONE, NONE), s2)));
        var own = new Story("S12", "T", "g", NONE, NONE, null, "Context S1x.", "- ok", null,
                List.of(new Task("S12.T1", "Do it.")));
        assertEquals(List.of(), rule(PlanValidator.validate(plan(story("S1", NONE, NONE), own)), PlanValidator.SELF_CONTAINED));
    }

    @Test
    void parallelStoriesMustNotShareTouches() {
        var same = plan(story("S1", NONE, List.of("src/a/")), story("S2", NONE, List.of("src/a/")));
        var all = rule(PlanValidator.validate(same), PlanValidator.TOUCHES_OVERLAP);
        assertEquals(1, all.size());
        assertTrue(all.get(0).message().contains("dependency"));
        var nested = plan(story("S1", NONE, List.of("src/a")), story("S2", NONE, List.of("src/a/b/C.java")));
        assertEquals(1, rule(PlanValidator.validate(nested), PlanValidator.TOUCHES_OVERLAP).size());
        var apart = plan(story("S1", NONE, List.of("src/a/")), story("S2", NONE, List.of("src/ab/")));
        assertEquals(List.of(), rule(PlanValidator.validate(apart), PlanValidator.TOUCHES_OVERLAP));
    }

    @Test
    void dependencyPathAllowsSharedTouches() {
        var direct = plan(story("S1", NONE, List.of("src/a/")), story("S2", List.of("S1"), List.of("src/a/")));
        assertEquals(List.of(), rule(PlanValidator.validate(direct), PlanValidator.TOUCHES_OVERLAP));
        var indirect = plan(story("S1", NONE, List.of("src/a/")), story("S2", List.of("S1"), List.of("x/")),
                story("S3", List.of("S2"), List.of("src/a/")));
        assertEquals(List.of(), rule(PlanValidator.validate(indirect), PlanValidator.TOUCHES_OVERLAP));
        var branch = plan(story("S1", NONE, List.of("r/")), story("S2", List.of("S1"), List.of("src/a/")),
                story("S3", List.of("S1"), List.of("src/a/")));
        assertEquals(1, rule(PlanValidator.validate(branch), PlanValidator.TOUCHES_OVERLAP).size());
    }

    @Test
    void limits() {
        var stories = new ArrayList<Story>();
        IntStream.rangeClosed(1, 50).forEach(i -> stories.add(story("S" + i, NONE, List.of("d" + i + "/"))));
        assertEquals(List.of(), rule(PlanValidator.validate(plan(stories.toArray(Story[]::new))), PlanValidator.LIMITS));
        stories.add(story("S51", NONE, List.of("d51/")));
        assertEquals(1, rule(PlanValidator.validate(plan(stories.toArray(Story[]::new))), PlanValidator.LIMITS).size());

        var tasks = new ArrayList<Task>();
        IntStream.rangeClosed(1, 20).forEach(i -> tasks.add(new Task("S1.T" + i, "x")));
        var twenty = story("S1", NONE, NONE).withTasks(tasks);
        assertEquals(List.of(), rule(PlanValidator.validate(plan(twenty)), PlanValidator.LIMITS));
        tasks.add(new Task("S1.T21", "x"));
        assertEquals(1, rule(PlanValidator.validate(plan(twenty.withTasks(tasks))), PlanValidator.LIMITS).size());

        var big = new Story("S1", "T", "g", NONE, NONE, null, "x".repeat(8_000), "- ok", null, List.of(new Task("S1.T1", "x")));
        assertEquals(1, rule(PlanValidator.validate(plan(big)), PlanValidator.LIMITS).size());
        var fits = new Story("S1", "T", "g", NONE, NONE, null, "x".repeat(7_000), "- ok", null, List.of(new Task("S1.T1", "x")));
        assertEquals(List.of(), rule(PlanValidator.validate(plan(fits)), PlanValidator.LIMITS));
    }

    @Test
    void formatNamesStoryAndRule() {
        var text = PlanValidator.format(PlanValidator.validate(plan(story("S1", List.of("S9"), NONE))));
        assertTrue(text.contains("Story S1 [depends-on]"));
    }
}
