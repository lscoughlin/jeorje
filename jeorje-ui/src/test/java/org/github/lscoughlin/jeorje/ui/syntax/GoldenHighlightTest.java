package org.github.lscoughlin.jeorje.ui.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Highlights a sample for every embedded syntax with the real JLine engine. The sample of a syntax is the
 * resource {@code syntax-samples/<name>.txt}. A syntax with no own sample uses {@code default.txt}.
 */
class GoldenHighlightTest {

    private static final String SAMPLE_DIR = "/syntax-samples/";

    @TempDir
    Path tmp;

    static String sampleFor(String syntaxName) throws IOException {
        var key = syntaxName.replaceAll("[^A-Za-z0-9_-]", "_");
        for (var candidate : List.of(key, "default")) {
            try (var in = GoldenHighlightTest.class.getResourceAsStream(SAMPLE_DIR + candidate + ".txt")) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("No sample");
    }

    private SyntaxCatalog catalog(String version) {
        return SyntaxCatalog.load(new SyntaxSettings(null, null, List.of(), List.of(), true,
                tmp.resolve("cache"), version));
    }

    @Test
    void everyEmbeddedSyntaxStylesItsSample() throws Exception {
        var catalog = catalog("golden");
        var unstyled = new ArrayList<String>();
        for (var syntax : catalog.syntaxes()) {
            var engine = HighlighterFactory.jline().create(syntax);
            var styled = false;
            for (var line : sampleFor(syntax.name()).split("\n")) {
                var out = engine.highlight(line);
                assertEquals(line, out.toString(), "text changed in " + syntax.name());
                for (var i = 0; i < out.length(); i++) {
                    styled |= !out.styleAt(i).equals(AttributedStyle.DEFAULT);
                }
            }
            if (!styled) {
                unstyled.add(syntax.name());
            }
        }
        assertTrue(unstyled.isEmpty(), "syntaxes with no styled token: " + unstyled);
    }

    @Test
    void multiSyntaxFilesGiveAllTheirSyntaxes() {
        var catalog = catalog("multi");
        for (var name : List.of("git-config", "git-commit", "git-rebase-todo", "Ebuild", "etc-portage",
                "Privoxy-config", "Privoxy-action", "Privoxy-filter")) {
            assertTrue(catalog.byName(name).isPresent(), "missing syntax " + name);
        }
    }

    @Test
    void newLanguagesAreWrittenForJeorje() {
        var catalog = catalog("new");
        for (var name : List.of("Objective-C", "Dart", "R", "Julia", "GraphQL", "Nix", "protobuf", "Erlang",
                "Guile", "Pascal", "ChangeLog", "log")) {
            assertTrue(catalog.byName(name).isPresent(), "missing syntax " + name);
        }
    }

    @Test
    void splitterKeepsHeaderAndColorLines() {
        var lines = List.of("## header", "syntax \"A\" \"\\.a$\"", "color red \"a\"", "", "syntax \"B\" \"\\.b$\"",
                "color blue \"b\"");
        var parts = SyntaxCatalog.splitNanorc("x.nanorc", lines);
        assertEquals(List.of("x.A.nanorc", "x.B.nanorc"), List.copyOf(parts.keySet()));
        assertEquals(List.of("## header", "syntax \"A\" \"\\.a$\"", "color red \"a\"", ""), parts.get("x.A.nanorc"));
        assertEquals(List.of("## header", "syntax \"B\" \"\\.b$\"", "color blue \"b\""), parts.get("x.B.nanorc"));
        assertEquals(List.of("x.nanorc"), List.copyOf(SyntaxCatalog.splitNanorc("x.nanorc", lines.subList(0, 3)).keySet()));
    }
}
