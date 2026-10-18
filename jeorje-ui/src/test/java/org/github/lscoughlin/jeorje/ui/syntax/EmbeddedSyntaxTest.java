package org.github.lscoughlin.jeorje.ui.syntax;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads every embedded syntax with the pinned JLine version. */
class EmbeddedSyntaxTest {

    @TempDir
    Path tmp;

    @Test
    void everyEmbeddedSyntaxLoadsAndHighlights() {
        var settings = new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), "all");
        var catalog = SyntaxCatalog.load(settings);
        assertTrue(catalog.syntaxes().size() >= 100, "syntaxes: " + catalog.syntaxes().size());
        var failures = new ArrayList<String>();
        for (var syntax : catalog.syntaxes()) {
            try {
                var engine = HighlighterFactory.jline().create(syntax);
                assertNotNull(engine);
                for (var line : List.of("public class Foo { // comment", "/* open", "still inside */ x = 1;", "")) {
                    AttributedString out = engine.highlight(line);
                    assertTrue(out.toString().equals(line), "text changed in " + syntax.name());
                }
            } catch (Exception | AssertionError e) {
                failures.add(syntax.name() + " (" + syntax.file().getFileName() + "): " + e);
            }
        }
        assertTrue(failures.isEmpty(), "failures: " + failures);
    }

    @Test
    void javaSyntaxAddsStyle() throws Exception {
        var catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), "j"));
        var java = catalog.byName("java").orElseThrow();
        var out = HighlighterFactory.jline().create(java).highlight("public class Foo {}");
        assertFalse(out.toAnsi().equals("public class Foo {}"), "expected color codes");
    }

    @Test
    void commonSyntaxesStyleTheirKeywords() throws Exception {
        var catalog = SyntaxCatalog.load(
                new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), "k"));
        var samples = java.util.Map.of("Main.java", "public class Foo {}", "a.py", "def foo(): return None",
                "a.json", "{\"a\": true}", "a.sh", "if true; then echo hi; fi", "a.go", "func main() {}");
        for (var sample : samples.entrySet()) {
            var syntax = catalog.forFileName(sample.getKey()).orElseThrow();
            var out = HighlighterFactory.jline().create(syntax).highlight(sample.getValue());
            var styled = false;
            for (var i = 0; i < out.length(); i++) {
                styled |= !out.styleAt(i).equals(org.jline.utils.AttributedStyle.DEFAULT);
            }
            assertTrue(styled, sample.getKey() + " has no style");
        }
    }
}
