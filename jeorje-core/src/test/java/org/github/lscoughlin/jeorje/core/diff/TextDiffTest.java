package org.github.lscoughlin.jeorje.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TextDiffTest {

    private static SplitText t(String s) {
        return SplitText.split(s);
    }

    private static Function<String, SplitText> files(Map<String, String> m) {
        return p -> m.containsKey(p) ? t(m.get(p)) : null;
    }

    // --- splitting

    @Test
    void splitsLinesAndRemembersTerminator() {
        var lf = t("a\nb\n");
        assertEquals(java.util.List.of("a", "b"), lf.lines());
        assertEquals("\n", lf.eol());
        assertTrue(lf.finalNewline());
        var crlf = t("a\r\nb\r\nc");
        assertEquals(java.util.List.of("a", "b", "c"), crlf.lines());
        assertEquals("\r\n", crlf.eol());
        assertFalse(crlf.finalNewline());
        assertEquals("a\r\nb\r\nc", crlf.render());
    }

    @Test
    void mixedTerminatorsRoundTrip() {
        var text = "a\r\nb\nc\r\nd\r\n";
        var s = t(text);
        assertEquals("\r\n", s.eol());
        assertEquals(text, s.render());
    }

    @Test
    void emptyAndBlankTexts() {
        assertEquals("", t("").render());
        assertTrue(t("").lines().isEmpty());
        assertEquals(java.util.List.of(""), t("\n").lines());
        assertEquals("a\n\n", t("a\n\n").render());
    }

    // --- binary and limits

    @Test
    void refusesBinary() {
        var zero = new byte[] {'a', 0, 'b'};
        assertEquals(TextDiffException.Kind.BINARY,
                assertThrows(TextDiffException.class, () -> SplitText.decode(zero)).kind());
        var bad = new byte[] {'a', (byte) 0xC3, 0x28};
        assertEquals(TextDiffException.Kind.BINARY,
                assertThrows(TextDiffException.class, () -> SplitText.decode(bad)).kind());
        assertEquals("héllo", SplitText.decode("héllo".getBytes(StandardCharsets.UTF_8)).render());
    }

    @Test
    void zeroByteAfterScanWindowIsNotBinary() {
        var bytes = new byte[9000];
        java.util.Arrays.fill(bytes, (byte) 'a');
        bytes[8500] = 0;
        assertEquals(9000, SplitText.decode(bytes).render().length());
    }

    @Test
    void refusesTooManyLines() {
        var a = t("1\n2\n3\n");
        var ex = assertThrows(TextDiffException.class,
                () -> TextDiff.unified("a", "b", a, a, TextDiff.Options.defaults().withMaxLines(2)));
        assertEquals(TextDiffException.Kind.TOO_LARGE, ex.kind());
    }

    @Test
    void timeoutIsReported() {
        var sb = new StringBuilder();
        var sb2 = new StringBuilder();
        for (var i = 0; i < 3000; i++) {
            sb.append("a").append(i).append('\n');
            sb2.append("b").append(i).append('\n');
        }
        var ex = assertThrows(TextDiffException.class, () -> TextDiff.unified("a", "b", t(sb.toString()),
                t(sb2.toString()), TextDiff.Options.defaults().withTimeout(Duration.ofNanos(1))));
        assertEquals(TextDiffException.Kind.TIMEOUT, ex.kind());
    }

    // --- generation

    @Test
    void unifiedDiffHasHeadersAndCounts() {
        var r = TextDiff.unified("a/f", "b/f", t("one\ntwo\nthree\n"), t("one\n2\nthree\nfour\n"),
                TextDiff.Options.defaults());
        assertTrue(r.unified().startsWith("--- a/f\n+++ b/f\n@@ -1,3 +1,4 @@\n"), r.unified());
        assertEquals(2, r.added());
        assertEquals(1, r.removed());
    }

    @Test
    void identicalTextsGiveNoDiff() {
        assertTrue(TextDiff.unified("a", "b", t("x\n"), t("x\n"), TextDiff.Options.defaults()).identical());
    }

    @Test
    void contextOptionChangesHunks() {
        var a = t("1\n2\n3\n4\n5\n6\n7\n8\n9\n");
        var b = t("1\n2\n3\n4\nX\n6\n7\n8\n9\n");
        var c0 = TextDiff.unified("a", "b", a, b, TextDiff.Options.defaults().withContext(0)).unified();
        assertTrue(c0.contains("@@ -5,1 +5,1 @@"), c0);
        var c1 = TextDiff.unified("a", "b", a, b, TextDiff.Options.defaults().withContext(1)).unified();
        assertTrue(c1.contains("@@ -4,3 +4,3 @@"), c1);
    }

    @Test
    void noNewlineMarkerIsWrittenAndRead() {
        var a = t("a\nb");
        var b = t("a\nb\n");
        var d = TextDiff.unified("a/f", "b/f", a, b, TextDiff.Options.defaults()).unified();
        assertTrue(d.contains("-b\n\\ No newline at end of file\n+b\n"), d);
        var applied = TextDiff.applyAll(d, -1, files(Map.of("f", "a\nb")), ApplyOptions.defaults());
        assertEquals("a\nb\n", applied.getFirst().result().render());
        var back = TextDiff.unified("a/f", "b/f", b, a, TextDiff.Options.defaults()).unified();
        assertTrue(back.contains("-b\n+b\n\\ No newline at end of file\n"), back);
        var undone = TextDiff.applyAll(back, -1, files(Map.of("f", "a\nb\n")), ApplyOptions.defaults());
        assertEquals("a\nb", undone.getFirst().result().render());
    }

    @Test
    void ignoreWhitespaceModes() {
        var a = t("x  \ny\n");
        var b = t("x\ny\n");
        assertFalse(TextDiff.unified("a", "b", a, b, TextDiff.Options.defaults()).identical());
        assertTrue(TextDiff.unified("a", "b", a, b,
                TextDiff.Options.defaults().withWhitespace(TextDiff.Whitespace.TRAILING)).identical());
        var c = t("a b\n");
        var d = t("ab\n");
        assertFalse(TextDiff.unified("a", "b", c, d,
                TextDiff.Options.defaults().withWhitespace(TextDiff.Whitespace.TRAILING)).identical());
        assertTrue(TextDiff.unified("a", "b", c, d,
                TextDiff.Options.defaults().withWhitespace(TextDiff.Whitespace.ALL)).identical());
    }

    @Test
    void bothAlgorithmsProduceValidDiffs() {
        var a = t("a\nb\nc\nd\ne\n");
        var b = t("a\nc\nd\nX\ne\nf\n");
        for (var algo : TextDiff.Algorithm.values()) {
            var d = TextDiff.unified("a/f", "b/f", a, b, TextDiff.Options.defaults().withAlgorithm(algo));
            var res = TextDiff.applyAll(d.unified(), -1, files(Map.of("f", a.render())), ApplyOptions.defaults());
            assertEquals(b.render(), res.getFirst().result().render());
        }
    }

    @Test
    void inlineViewMarksChangedWords() {
        var rows = TextDiff.inline(t("keep\nthe quick brown fox\n"), t("keep\nthe slow brown fox\nnew line\n"),
                TextDiff.Options.defaults());
        assertEquals(2, rows.size());
        assertEquals(TextDiff.InlineRow.Tag.CHANGE, rows.get(0).tag());
        assertEquals(2, rows.get(0).oldNumber());
        assertTrue(rows.get(0).oldLine().contains("[-quick-]"), rows.get(0).toString());
        assertTrue(rows.get(0).newLine().contains("{+slow+}"), rows.get(0).toString());
        assertEquals(TextDiff.InlineRow.Tag.INSERT, rows.get(1).tag());
        assertEquals(3, rows.get(1).newNumber());
        assertTrue(TextDiff.renderInline(rows).contains("+ 3: new line"));
    }

    // --- apply

    @Test
    void appliesMultiFileDiffWithCreateAndDelete() {
        var diff = """
                --- a/one.txt
                +++ b/one.txt
                @@ -1,2 +1,2 @@
                -x
                +y
                 z
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -bye
                -now
                """;
        var res = TextDiff.applyAll(diff, -1, files(Map.of("one.txt", "x\nz\n", "gone.txt", "bye\nnow\n")),
                ApplyOptions.defaults());
        assertEquals(3, res.size());
        assertEquals("one.txt", res.get(0).path());
        assertEquals("y\nz\n", res.get(0).result().render());
        assertEquals("new.txt", res.get(1).path());
        assertTrue(res.get(1).created());
        assertEquals("hello\nworld\n", res.get(1).result().render());
        assertTrue(res.get(2).deleted());
        assertEquals("gone.txt", res.get(2).path());
        assertTrue(TextDiff.allApplied(res));
    }

    @Test
    void reverseUndoesCreateAndDelete() {
        var diff = "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hello\n";
        var res = TextDiff.applyAll(diff, -1, files(Map.of("new.txt", "hello\n")),
                ApplyOptions.defaults().withReverse(true));
        assertTrue(res.getFirst().deleted());
        var del = "--- a/g.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-bye\n";
        var back = TextDiff.applyAll(del, -1, files(Map.of()), ApplyOptions.defaults().withReverse(true));
        assertTrue(back.getFirst().created());
        assertEquals("bye\n", back.getFirst().result().render());
    }

    @Test
    void createFailsIfFileHasContentAndModifyFailsIfMissing() {
        var create = "--- /dev/null\n+++ b/f\n@@ -0,0 +1 @@\n+x\n";
        var r1 = TextDiff.applyAll(create, -1, files(Map.of("f", "old\n")), ApplyOptions.defaults());
        assertNotNull(r1.getFirst().error());
        var modify = "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n+y\n";
        var r2 = TextDiff.applyAll(modify, -1, files(Map.of()), ApplyOptions.defaults());
        assertEquals(FileApplyResult.Status.FAILED, r2.getFirst().status());
        assertNotNull(r2.getFirst().error());
    }

    @Test
    void appliesAtOffset() {
        var diff = "--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n";
        var res = TextDiff.applyAll(diff, -1, files(Map.of("f", "p\nq\nr\na\nb\nc\n")), ApplyOptions.defaults());
        var hunk = res.getFirst().hunks().getFirst();
        assertEquals(3, hunk.offset());
        assertEquals(0, hunk.fuzz());
        assertEquals("p\nq\nr\na\nB\nc\n", res.getFirst().result().render());
    }

    @Test
    void appliesWithFuzzAndReportsIt() {
        var diff = "--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n WRONG\n-b\n+B\n c\n";
        var strict = TextDiff.applyAll(diff, -1, files(Map.of("f", "a\nb\nc\n")), ApplyOptions.defaults().withFuzz(0));
        assertEquals(FileApplyResult.Status.FAILED, strict.getFirst().status());
        var res = TextDiff.applyAll(diff, -1, files(Map.of("f", "a\nb\nc\n")), ApplyOptions.defaults().withFuzz(1));
        assertEquals("a\nB\nc\n", res.getFirst().result().render());
        assertEquals(1, res.getFirst().hunks().getFirst().fuzz());
    }

    @Test
    void keepsTerminatorsOfUnchangedLines() {
        var diff = "--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n";
        var res = TextDiff.applyAll(diff, -1, files(Map.of("f", "a\r\nb\nc\r\n")), ApplyOptions.defaults());
        assertEquals("a\r\nB\r\nc\r\n", res.getFirst().result().render());
        var mixed = TextDiff.applyAll(diff, -1, files(Map.of("f", "a\nb\nc\nd\r\ne\r\nf\r\n")), ApplyOptions.defaults());
        assertEquals("a\nB\nc\nd\r\ne\r\nf\r\n", mixed.getFirst().result().render());
    }

    @Test
    void failureReportHasNearbyLines() {
        var diff = "--- a/f\n+++ b/f\n@@ -3,2 +3,2 @@\n nope\n-missing\n+x\n";
        var res = TextDiff.applyAll(diff, -1, files(Map.of("f", "l1\nl2\nl3\nl4\nl5\nl6\n")), ApplyOptions.defaults());
        var r = res.getFirst();
        assertEquals(FileApplyResult.Status.FAILED, r.status());
        assertNull(r.result());
        var failure = r.failures().getFirst();
        assertEquals(1, failure.index());
        assertEquals(3, failure.expectedLine());
        assertTrue(failure.header().startsWith("@@ -3,2"));
        assertTrue(failure.nearbyLines().contains("3: l3"), failure.nearbyLines().toString());
        assertFalse(failure.reason().isEmpty());
        assertFalse(TextDiff.allApplied(res));
    }

    @Test
    void oneFailedHunkDoesNotHideOthers() {
        var diff = "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-zzz\n+yyy\n@@ -3 +3 @@\n-l3\n+L3\n";
        var res = TextDiff.applyAll(diff, -1, files(Map.of("f", "l1\nl2\nl3\n")), ApplyOptions.defaults());
        assertEquals(1, res.getFirst().failures().size());
        assertEquals(1, res.getFirst().hunks().size());
        assertEquals(2, res.getFirst().hunks().getFirst().index());
    }

    @Test
    void conflictMarkersMode() {
        var diff = "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-zzz\n+yyy\n@@ -3 +3 @@\n-l3\n+L3\n";
        var opts = ApplyOptions.defaults().withConflicts(ApplyOptions.Conflicts.MARKERS);
        var r = TextDiff.applyAll(diff, -1, files(Map.of("f", "l1\nl2\nl3\n")), opts).getFirst();
        assertEquals(FileApplyResult.Status.CONFLICTED, r.status());
        assertEquals("<<<<<<< current\nl1\n=======\nyyy\n>>>>>>> patch\nl2\nL3\n", r.result().render());
        assertEquals(1, r.failures().size());
    }

    @Test
    void stripOptions() {
        var diff = "--- a/src/f\n+++ b/src/f\n@@ -1 +1 @@\n-x\n+y\n";
        var file = TextDiff.parse(diff).getFirst();
        assertEquals("src/f", file.path(-1));
        assertEquals("f", file.path(2));
        assertEquals("a/src/f".replace("a/", "b/"), file.path(0));
        var plain = TextDiff.parse("--- src/f\n+++ src/f\n@@ -1 +1 @@\n-x\n+y\n").getFirst();
        assertEquals("src/f", plain.path(-1));
    }

    @Test
    void renamesAndModeChangesAreErrors() {
        var rename = "diff --git a/a b/b\nsimilarity index 100%\nrename from a\nrename to b\n";
        assertEquals(TextDiffException.Kind.UNSUPPORTED,
                assertThrows(TextDiffException.class, () -> TextDiff.parse(rename)).kind());
        var mode = "diff --git a/a b/a\nold mode 100644\nnew mode 100755\n";
        assertEquals(TextDiffException.Kind.UNSUPPORTED,
                assertThrows(TextDiffException.class, () -> TextDiff.parse(mode)).kind());
    }

    @Test
    void binaryAndMalformedDiffsAreErrors() {
        assertEquals(TextDiffException.Kind.BINARY, assertThrows(TextDiffException.class,
                () -> TextDiff.parse("diff --git a/x b/x\nBinary files a/x and b/x differ\n")).kind());
        assertEquals(TextDiffException.Kind.MALFORMED,
                assertThrows(TextDiffException.class, () -> TextDiff.parse("just text\n")).kind());
        assertEquals(TextDiffException.Kind.MALFORMED, assertThrows(TextDiffException.class,
                () -> TextDiff.parse("--- a/f\n+++ b/f\n@@ -x @@\n")).kind());
    }

    @Test
    void gitStyleHeadersAreAccepted() {
        var diff = "diff --git a/f b/f\nnew file mode 100644\nindex 0000000..e69de29\n--- /dev/null\n+++ b/f\n@@ -0,0 +1 @@\n+hi\n";
        var file = TextDiff.parse(diff).getFirst();
        assertEquals(FileDiff.Kind.CREATE, file.kind());
        assertEquals("f", file.path(-1));
    }

    @Test
    void timestampsInHeadersAreIgnored() {
        var diff = "--- a/f\t2024-01-01 10:00:00\n+++ b/f\t2024-01-02 10:00:00\n@@ -1 +1 @@\n-x\n+y\n";
        assertEquals("a/f", TextDiff.parse(diff).getFirst().oldName());
    }
}
