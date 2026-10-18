package org.github.lscoughlin.jeorje.ui.editor.markdown;

import static org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type.CONTENT;
import static org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type.FENCE_CLOSE;
import static org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type.FENCE_OPEN;
import static org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type.FRONT_MATTER;
import static org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.github.lscoughlin.jeorje.ui.editor.markdown.MarkdownRegions.Type;
import org.junit.jupiter.api.Test;

class MarkdownRegionsTest {

    private static List<Type> scan(String... lines) {
        return MarkdownRegions.of(List.of(lines)).types();
    }

    @Test
    void backtickFence() {
        assertEquals(List.of(TEXT, FENCE_OPEN, CONTENT, CONTENT, FENCE_CLOSE, TEXT),
                scan("a", "```java", "x", "y", "```", "b"));
    }

    @Test
    void tildeFence() {
        assertEquals(List.of(FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("~~~py", "x", "~~~"));
    }

    @Test
    void indentUpToThreeSpaces() {
        assertEquals(List.of(FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("   ```", "x", "   ```"));
        assertEquals(List.of(TEXT, TEXT, TEXT), scan("    ```", "x", "    ```"));
    }

    @Test
    void fenceInsideListItemAndQuote() {
        assertEquals(List.of(FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("- ```java", "  x", "  ```"));
        assertEquals(List.of(TEXT, FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("1. item", "   ```sh", "   ls", "   ```"));
        assertEquals(List.of(FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("> ```js", "> x", "> ```"));
        assertEquals(List.of(FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("> > ```js", "> > x", "> > ```"));
    }

    @Test
    void indentedFenceUnderNestedList() {
        assertEquals(List.of(TEXT, TEXT, FENCE_OPEN, CONTENT, FENCE_CLOSE),
                scan("- a", "  - b", "      ```", "      x", "      ```"));
    }

    @Test
    void closingFenceNeedsSameCharacterAndLength() {
        assertEquals(List.of(FENCE_OPEN, CONTENT, CONTENT, CONTENT, FENCE_CLOSE),
                scan("````", "```", "~~~~", "x", "`````"));
        assertEquals(List.of(FENCE_OPEN, CONTENT, FENCE_CLOSE), scan("~~~~", "~~~", "~~~~~~"));
    }

    @Test
    void closingFenceHasNothingElse() {
        assertEquals(List.of(FENCE_OPEN, CONTENT, CONTENT, FENCE_CLOSE), scan("```", "``` java", "```x", "```  "));
    }

    @Test
    void unclosedFenceRunsToTheEnd() {
        assertEquals(List.of(TEXT, FENCE_OPEN, CONTENT, CONTENT), scan("a", "```", "x", "y"));
        var regions = MarkdownRegions.of(List.of("a", "```", "x", "y"));
        assertEquals(3, regions.blockEnd(1));
    }

    @Test
    void fenceInFenceStaysInOuterBlock() {
        assertEquals(List.of(FENCE_OPEN, CONTENT, CONTENT, CONTENT, FENCE_CLOSE, TEXT),
                scan("````markdown", "```java", "x", "```", "````", "text"));
        assertEquals(List.of(FENCE_OPEN, CONTENT, CONTENT, FENCE_CLOSE), scan("```", "```java", "x", "```"));
    }

    @Test
    void backtickInfoStringCannotContainBacktick() {
        assertEquals(List.of(TEXT, TEXT), scan("```a`b", "x"));
        assertEquals(List.of(FENCE_OPEN, CONTENT), scan("~~~a`b", "x"));
    }

    @Test
    void shortRunIsNotAFence() {
        assertEquals(List.of(TEXT, TEXT), scan("``", "~~"));
    }

    @Test
    void infoAndBlockQueries() {
        var regions = MarkdownRegions.of(List.of("t", "> ```{r, echo=FALSE}", "> x", "> ```", "u"));
        assertEquals("{r, echo=FALSE}", regions.fence(2).info());
        assertEquals("{r, echo=FALSE}", regions.fence(3).info());
        assertEquals(1, regions.blockStart(3));
        assertEquals(3, regions.blockEnd(1));
        assertEquals(2, regions.fence(1).column());
    }

    @Test
    void frontMatterYamlMapping() {
        assertEquals(List.of(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, TEXT),
                scan("---", "title: A", "tags:", "---", "# H"));
        assertEquals(List.of(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, TEXT),
                scan("---", "# comment", "a: 1", "...", "x"));
    }

    @Test
    void frontMatterThatIsNotAMappingIsText() {
        assertEquals(List.of(TEXT, TEXT, TEXT), scan("---", "just words", "---"));
        assertEquals(List.of(TEXT, TEXT, TEXT), scan("---", "- a", "---"));
        assertEquals(List.of(TEXT, TEXT), scan("---", "---"));
        assertEquals(List.of(TEXT, TEXT), scan("---", "a: 1"));
        assertEquals(List.of(TEXT, TEXT, TEXT), scan("x", "---", "---"));
    }

    @Test
    void frontMatterFollowedByFence() {
        assertEquals(List.of(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, FENCE_OPEN, CONTENT, FENCE_CLOSE),
                scan("---", "a: b", "---", "```", "x", "```"));
    }

    @Test
    void fenceInsideFrontMatterIsNotAFence() {
        assertEquals(List.of(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, TEXT),
                scan("---", "a: b", "  ```", "---", "x"));
    }

    @Test
    void openingAFenceChangesLinesBelow() {
        var regions = MarkdownRegions.of(List.of("a", "b", "c"));
        regions.replace(1, 0, List.of("```java"));
        assertEquals(List.of(TEXT, FENCE_OPEN, CONTENT, CONTENT), regions.types());
        regions.replace(3, 0, List.of("```"));
        assertEquals(List.of(TEXT, FENCE_OPEN, CONTENT, FENCE_CLOSE, TEXT), regions.types());
    }

    @Test
    void incrementalScanStopsEarly() {
        var lines = new ArrayList<String>();
        for (var i = 0; i < 200; i++) {
            lines.add("line " + i);
        }
        var regions = MarkdownRegions.of(lines);
        regions.replace(100, 1, List.of("changed"));
        assertEquals(100, regions.lastScanStart());
        assertTrue(regions.lastScanEnd() <= 102, "end " + regions.lastScanEnd());
    }

    @Test
    void editInsideBlockResumesAtTheEdit() {
        var lines = new ArrayList<String>(List.of("```"));
        for (var i = 0; i < 100; i++) {
            lines.add("code " + i);
        }
        lines.add("```");
        lines.add("after");
        var regions = MarkdownRegions.of(lines);
        regions.replace(50, 1, List.of("edited"));
        lines.set(50, "edited");
        assertEquals(50, regions.lastScanStart());
        assertTrue(regions.lastScanEnd() < 53);
        assertEquals(MarkdownRegions.of(lines).snapshot(), regions.snapshot());
    }

    @Test
    void closingTheFenceFixesLinesBelow() {
        var regions = MarkdownRegions.of(List.of("```", "a", "b", "```", "c"));
        regions.replace(3, 1, List.of("x"));
        assertEquals(List.of(FENCE_OPEN, CONTENT, CONTENT, CONTENT, CONTENT), regions.types());
        regions.replace(3, 1, List.of("```"));
        assertEquals(List.of(FENCE_OPEN, CONTENT, CONTENT, FENCE_CLOSE, TEXT), regions.types());
    }

    @Test
    void frontMatterEditedIncrementally() {
        var regions = MarkdownRegions.of(List.of("---", "a: 1", "text", "more"));
        assertEquals(List.of(TEXT, TEXT, TEXT, TEXT), regions.types());
        regions.replace(2, 1, List.of("---"));
        assertEquals(List.of(FRONT_MATTER, FRONT_MATTER, FRONT_MATTER, TEXT), regions.types());
        regions.replace(0, 1, List.of("x"));
        assertEquals(List.of(TEXT, TEXT, TEXT, TEXT), regions.types());
    }

    @Test
    void randomEditsMatchFullScan() {
        var pool = List.of("```", "```java", "````", "~~~", "~~~~", "text", "", "---", "...", "a: 1", "- item",
                "  ```", "> ```", "> text", "- ```py", "  - nested", "    ```", "key: value", "# head");
        for (var seed = 0; seed < 40; seed++) {
            var random = new Random(seed);
            var model = new ArrayList<String>();
            for (var i = 0; i < random.nextInt(30); i++) {
                model.add(pool.get(random.nextInt(pool.size())));
            }
            var regions = MarkdownRegions.of(model);
            for (var step = 0; step < 200; step++) {
                var first = random.nextInt(model.size() + 1);
                var removed = random.nextInt(Math.min(4, model.size() - first) + 1);
                var inserted = new ArrayList<String>();
                for (var i = random.nextInt(4); i > 0; i--) {
                    inserted.add(pool.get(random.nextInt(pool.size())));
                }
                model.subList(first, first + removed).clear();
                model.addAll(first, inserted);
                regions.replace(first, removed, inserted);
                assertEquals(MarkdownRegions.of(model).snapshot(), regions.snapshot(),
                        "seed " + seed + " step " + step + " model " + model);
            }
        }
    }
}
