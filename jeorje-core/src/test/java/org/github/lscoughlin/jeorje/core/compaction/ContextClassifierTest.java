package org.github.lscoughlin.jeorje.core.compaction;

import static org.github.lscoughlin.jeorje.core.compaction.CompactionTestSupport.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.compaction.ContextClassifier.Context;
import org.junit.jupiter.api.Test;

class ContextClassifierTest {
    private final ContextClassifier classifier = new ContextClassifier();

    @Test
    void baseRules() {
        var user = chunk(1, "user", null, "fix it");
        var original = chunk(2, "mixed", null, "USER: build it\nASSISTANT: ok\n");
        var later = chunk(3, "mixed", null, "USER: next\nASSISTANT: ok\n");
        var reasoning = chunk(4, "assistant", null, "ASSISTANT: I think the plan is to refactor");
        var summary = chunk(5, "subagent_summary", null, "done");
        var read = chunk(6, "tool_result", "read", "TOOL_RESULT read file");
        var todo = chunk(7, "tool_result", "todowrite", "1. one");
        var recall = chunk(8, "tool_call", "recall", "RECALL query=x returned=a:1.0");
        var c = classifier.classify(List.of(user, original, later, reasoning, summary, read, todo, recall), Context.of(20));
        assertEquals(Protection.PROTECTED, c.get(user.chunkId()));
        assertEquals(Protection.PROTECTED, c.get(original.chunkId()));
        assertEquals(Protection.CAREFUL, c.get(later.chunkId()));
        assertEquals(Protection.CAREFUL, c.get(reasoning.chunkId()));
        assertEquals(Protection.CAREFUL, c.get(summary.chunkId()));
        assertEquals(Protection.AGGRESSIVE, c.get(read.chunkId()));
        assertEquals(Protection.PROTECTED, c.get(todo.chunkId()));
        assertEquals(Protection.AGGRESSIVE, c.get(recall.chunkId()));
    }

    @Test
    void pinnedChunkIsProtected() {
        var read = chunk(6, "tool_result", "read", "TOOL_RESULT read file");
        var ctx = new Context(20, 6, Set.of(read.chunkId()), Set.of(), Map.of(), 20_000);
        assertEquals(Protection.PROTECTED, classifier.classify(List.of(read), ctx).get(read.chunkId()));
    }

    @Test
    void currentErrorIsProtectedAndAResolvedErrorIsAggressive() {
        var fail = chunk(3, "tool_result", "bash", "TOOL_RESULT bash [ERROR] BUILD FAILURE in Foo.java");
        var other = chunk(4, "tool_result", "read", "TOOL_RESULT read fine");
        assertEquals(Protection.PROTECTED, classifier.classify(List.of(fail, other), Context.of(10)).get(fail.chunkId()));

        var ok = chunk(5, "tool_result", "bash", "TOOL_RESULT bash BUILD SUCCESS");
        var resolved = classifier.classify(List.of(fail, other, ok), Context.of(10));
        assertEquals(Protection.AGGRESSIVE, resolved.get(fail.chunkId()));

        var ctx = new Context(10, 6, Set.of(), Set.of(fail.chunkId()), Map.of(), 20_000);
        assertEquals(Protection.AGGRESSIVE, classifier.classify(List.of(fail, other), ctx).get(fail.chunkId()));
    }

    @Test
    void onlyTheLatestFailureOfAToolIsProtected() {
        var f1 = chunk(1, "tool_result", "bash", "TOOL_RESULT bash [ERROR] one");
        var f2 = chunk(2, "tool_result", "bash", "TOOL_RESULT bash [ERROR] two");
        var c = classifier.classify(List.of(f1, f2), Context.of(10));
        assertEquals(Protection.AGGRESSIVE, c.get(f1.chunkId()));
        assertEquals(Protection.PROTECTED, c.get(f2.chunkId()));
    }

    @Test
    void skillContentIsProtectedUpToTheLimitThenTheLeastRecentlyUsedGoesAggressive() {
        var s1 = chunk(1, "tool_result", "skill", "x".repeat(400));
        var s2 = chunk(2, "tool_result", "skill", "y".repeat(400));
        var s3 = chunk(3, "tool_result", "skill", "z".repeat(400));
        var within = classifier.classify(List.of(s1, s2, s3), new Context(10, 6, Set.of(), Set.of(), Map.of(), 250));
        assertEquals(Protection.PROTECTED, within.get(s3.chunkId()));
        assertEquals(Protection.PROTECTED, within.get(s2.chunkId()));
        assertEquals(Protection.AGGRESSIVE, within.get(s1.chunkId()));
        var used = new Context(10, 6, Set.of(), Set.of(), Map.of(s1.chunkId(), 9), 250);
        var c = classifier.classify(List.of(s1, s2, s3), used);
        assertEquals(Protection.PROTECTED, c.get(s1.chunkId()));
        assertEquals(Protection.AGGRESSIVE, c.get(s2.chunkId()));
        var all = classifier.classify(List.of(s1, s2, s3), Context.of(10));
        assertTrue(all.values().stream().allMatch(p -> p == Protection.PROTECTED));
    }

    @Test
    void immediatePathsComeFromRecentFileToolsAndErrors() {
        var oldRead = chunk(1, "tool_result", "read", "TOOL_RESULT read src/Old.java");
        var newRead = chunk(9, "tool_result", "read", "TOOL_RESULT read src/New.java");
        var fail = chunk(2, "tool_result", "bash", "[ERROR] src/Broken.java:12 failed");
        var ctx = Context.of(10);
        var classes = classifier.classify(List.of(oldRead, newRead, fail), ctx);
        var paths = classifier.immediatePaths(List.of(oldRead, newRead, fail), ctx, classes);
        assertTrue(paths.contains("src/New.java"));
        assertTrue(paths.contains("src/Broken.java:12") || paths.contains("src/Broken.java"));
        assertTrue(!paths.contains("src/Old.java"));
    }
}
