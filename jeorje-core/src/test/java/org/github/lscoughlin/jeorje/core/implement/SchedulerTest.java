package org.github.lscoughlin.jeorje.core.implement;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.plan.Plan;
import org.github.lscoughlin.jeorje.core.plan.Story;
import org.github.lscoughlin.jeorje.core.plan.Task;
import org.junit.jupiter.api.Test;

class SchedulerTest {
    private static Story story(String id, List<String> deps, String... touches) {
        return new Story(id, "Title " + id, "goal", deps, List.of(touches), null, "", "", null,
            List.of(new Task(id + ".T1", "task")));
    }

    private static Plan plan(Story... stories) {
        return new Plan(Map.of("title", "T", "status", "approved"), List.of(), "", List.of(stories), "");
    }

    private static Map<String, StoryState> states(Plan plan, StoryState state) {
        var map = new LinkedHashMap<String, StoryState>();
        plan.stories().forEach(s -> map.put(s.id(), state));
        return map;
    }

    private static List<String> ids(List<Story> stories) { return stories.stream().map(Story::id).toList(); }

    @Test
    void startsOnlyStoriesWhoseDependenciesAreDone() {
        var plan = plan(story("S1", List.of(), "a"), story("S2", List.of("S1"), "b"), story("S3", List.of(), "c"));
        var states = states(plan, StoryState.PENDING);
        assertEquals(List.of("S1", "S3"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3"), 5)));
        states.put("S1", StoryState.RUNNING);
        assertEquals(List.of("S3"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3"), 5)));
        states.put("S1", StoryState.DONE);
        assertEquals(List.of("S2", "S3"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3"), 5)));
    }

    @Test
    void neverExceedsTheParallelLimit() {
        var plan = plan(story("S1", List.of(), "a"), story("S2", List.of(), "b"), story("S3", List.of(), "c"));
        var states = states(plan, StoryState.PENDING);
        assertEquals(List.of("S1", "S2"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3"), 2)));
        states.put("S1", StoryState.RUNNING);
        assertEquals(List.of("S2"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3"), 2)));
        states.put("S2", StoryState.RUNNING);
        assertEquals(List.of(), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3"), 2)));
    }

    @Test
    void storiesWithSharedTouchesDoNotRunTogether() {
        var plan = plan(story("S1", List.of(), "src/main/"), story("S2", List.of(), "src/main/java/A.java"),
            story("S3", List.of(), "docs"), story("S4", List.of(), "src/main"));
        var states = states(plan, StoryState.PENDING);
        assertEquals(List.of("S1", "S3"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3", "S4"), 9)));
        states.put("S1", StoryState.RUNNING);
        assertEquals(List.of("S3"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3", "S4"), 9)));
    }

    @Test
    void touchesOverlapUsesPathSegments() {
        assertTrue(Scheduler.touchesOverlap(List.of("src/a"), List.of("src/a/B.java")));
        assertTrue(Scheduler.touchesOverlap(List.of("./src/a/"), List.of("src/a")));
        assertFalse(Scheduler.touchesOverlap(List.of("src/a"), List.of("src/ab")));
        assertFalse(Scheduler.touchesOverlap(List.of(), List.of("x")));
    }

    @Test
    void blockedStoriesBlockTheirDependentsTransitively() {
        var plan = plan(story("S1", List.of(), "a"), story("S2", List.of("S1"), "b"),
            story("S3", List.of("S2"), "c"), story("S4", List.of(), "d"));
        var states = states(plan, StoryState.PENDING);
        states.put("S1", StoryState.BLOCKED);
        assertEquals(List.of("S2", "S3"), Scheduler.blockedDependents(plan, states));
        assertEquals(List.of("S4"), ids(Scheduler.next(plan, states, Set.of("S1", "S2", "S3", "S4"), 5)));
    }

    @Test
    void theClosureOfNamedStoriesHasTheirOpenDependencies() {
        var plan = plan(story("S1", List.of(), "a"), story("S2", List.of("S1"), "b"),
            story("S3", List.of("S2"), "c"), story("S4", List.of(), "d"));
        assertEquals(Set.of("S3", "S2", "S1"), Scheduler.closure(plan, Set.of("S3")));
        assertEquals(Set.of("S1", "S2", "S3", "S4"), Scheduler.closure(plan, Set.of()));
        var done = new Story("S1", "T", "g", List.of(), List.of("a"), null, "", "", null,
            List.of(new Task("S1.T1", "x").withState(org.github.lscoughlin.jeorje.core.plan.TaskState.DONE)));
        var plan2 = plan(done, story("S2", List.of("S1"), "b"));
        assertEquals(Set.of("S2"), Scheduler.closure(plan2, Set.of("S2")));
    }
}
