package org.github.lscoughlin.jeorje.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextDiffPropertyTest {

    private static final String[] WORDS = {"alpha", "beta", "gamma", "", "  indented", "x = 1;", "}", "{", "tab\there"};

    private static List<String> randomLines(Random r, int max) {
        var out = new ArrayList<String>();
        var n = r.nextInt(max + 1);
        for (var i = 0; i < n; i++) {
            out.add(WORDS[r.nextInt(WORDS.length)] + (r.nextInt(4) == 0 ? r.nextInt(5) : ""));
        }
        return out;
    }

    private static List<String> edit(Random r, List<String> in) {
        var out = new ArrayList<>(in);
        var edits = r.nextInt(6);
        for (var i = 0; i < edits; i++) {
            switch (r.nextInt(3)) {
                case 0 -> out.add(r.nextInt(out.size() + 1), "new" + r.nextInt(100));
                case 1 -> {
                    if (!out.isEmpty()) {
                        out.remove(r.nextInt(out.size()));
                    }
                }
                default -> {
                    if (!out.isEmpty()) {
                        out.set(r.nextInt(out.size()), "changed" + r.nextInt(100));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void patchOfDiffGivesTargetAndReverseGivesSource() {
        var r = new Random(42);
        for (var round = 0; round < 1500; round++) {
            var aLines = randomLines(r, 25);
            var bLines = edit(r, aLines);
            var aFinal = r.nextInt(3) != 0;
            var bFinal = r.nextInt(3) != 0;
            var noTerminator = aLines.size() + (aFinal ? 1 : 0) < 2 || bLines.size() + (bFinal ? 1 : 0) < 2;
            var eol = noTerminator || r.nextBoolean() ? "\n" : "\r\n";
            var a = SplitText.ofLines(aLines, aLines.isEmpty() ? "\n" : eol, aFinal);
            var b = SplitText.ofLines(bLines, bLines.isEmpty() ? "\n" : eol, bFinal);
            var textA = SplitText.split(a.render());
            var textB = SplitText.split(b.render());
            var context = r.nextInt(4);
            var options = TextDiff.Options.defaults().withContext(context)
                    .withAlgorithm(r.nextBoolean() ? TextDiff.Algorithm.MYERS : TextDiff.Algorithm.MYERS_LINEAR);
            var diff = TextDiff.unified("a/f.txt", "b/f.txt", textA, textB, options);
            var label = "round " + round + " eol=" + (eol.length() == 2 ? "crlf" : "lf") + "\n" + diff.unified();
            if (textA.render().equals(textB.render())) {
                assertTrue(diff.identical(), label);
                continue;
            }
            var forward = TextDiff.applyAll(diff.unified(), -1, p -> textA, ApplyOptions.defaults().withFuzz(0));
            assertTrue(TextDiff.allApplied(forward), label);
            assertEquals(textB.render(), forward.getFirst().result().render(), label);
            var back = TextDiff.applyAll(diff.unified(), -1, p -> textB, ApplyOptions.defaults().withFuzz(0).withReverse(true));
            assertTrue(TextDiff.allApplied(back), label);
            assertEquals(textA.render(), back.getFirst().result().render(), label);
        }
    }
}
