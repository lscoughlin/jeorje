package org.github.lscoughlin.jeorje.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrontMatterTest {
    static final FrontMatter.Limits LIMITS = FrontMatter.Limits.defaults();

    static List<String> lines(String... lines) { return Arrays.asList(lines); }

    @ParameterizedTest
    @ValueSource(strings = {"//", "#", "*", "--", ";", "%", "<!--", "/*", ""})
    void everyPrefixIsAccepted(String prefix) {
        var p = prefix.isEmpty() ? "" : prefix + " ";
        var found = FrontMatter.scan(lines("package x;", p + "---", p + "title: Demo", p + "tags: [a, b]", p + "---", "code"), LIMITS);
        assertEquals("title: Demo\ntags: [a, b]", found.orElseThrow(), prefix);
    }

    @Test
    void markerAtLine25IsFoundAndLine26IsIgnored() {
        var at25 = new ArrayList<String>();
        for (var i = 0; i < 24; i++) at25.add("x");
        at25.addAll(List.of("---", "a: 1", "---"));
        assertTrue(FrontMatter.scan(at25, LIMITS).isPresent());
        var at26 = new ArrayList<String>();
        for (var i = 0; i < 25; i++) at26.add("x");
        at26.addAll(List.of("---", "a: 1", "---"));
        assertTrue(FrontMatter.scan(at26, LIMITS).isEmpty());
    }

    @Test
    void noClosingMarkerMeansNone() {
        assertTrue(FrontMatter.scan(lines("---", "a: 1", "b: 2"), LIMITS).isEmpty());
    }

    @Test
    void closingMarkerNeedsTheSamePrefix() {
        assertTrue(FrontMatter.scan(lines("// ---", "// a: 1", "# ---"), LIMITS).isEmpty());
    }

    @Test
    void blockOverFortyLinesIsIgnored() {
        var ok = new ArrayList<String>(List.of("---"));
        for (var i = 0; i < 38; i++) ok.add("k" + i + ": v");
        ok.add("---");
        assertEquals(40, ok.size());
        assertTrue(FrontMatter.scan(ok, LIMITS).isPresent());
        var big = new ArrayList<String>(List.of("---"));
        for (var i = 0; i < 40; i++) big.add("k" + i + ": v");
        big.add("---");
        assertTrue(FrontMatter.scan(big, LIMITS).isEmpty());
    }

    @Test
    void markdownHeadingUnderlineAndRulesAreNotFrontMatter() {
        assertTrue(FrontMatter.scan(lines("Title", "---", "", "Some text here.", "---", "more"), LIMITS).isEmpty());
        assertTrue(FrontMatter.scan(lines("# Head", "", "---", "", "text", "", "---", ""), LIMITS).isEmpty());
        assertTrue(FrontMatter.scan(lines("---", "---", "text"), LIMITS).isEmpty());
        assertTrue(FrontMatter.scan(lines("-----", "a: 1", "-----"), LIMITS).isEmpty());
    }

    @Test
    void laterBlockIsFoundAfterABadOne() {
        var found = FrontMatter.scan(lines("---", "just text", "---", "a: 1", "---"), LIMITS);
        assertEquals("a: 1", found.orElseThrow());
    }

    @Test
    void invalidYamlIsIgnored() {
        assertTrue(FrontMatter.scan(lines("---", "a: [1", "---"), LIMITS).isEmpty());
    }
}
