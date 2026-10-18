package org.github.lscoughlin.jeorje.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageResolverTest {

    @TempDir
    Path tmp;

    @Test
    void tagForms() {
        assertEquals("java", LanguageResolver.tagOf("java"));
        assertEquals("java", LanguageResolver.tagOf("Java title=\"x\""));
        assertEquals("java", LanguageResolver.tagOf("{java}"));
        assertEquals("java", LanguageResolver.tagOf("{.java}"));
        assertEquals("r", LanguageResolver.tagOf("{r, echo=FALSE}"));
        assertEquals("", LanguageResolver.tagOf("  "));
        assertEquals("", LanguageResolver.tagOf(null));
    }

    @Test
    void resolvesBySyntaxNameAliasAndFileName() {
        var resolver = new LanguageResolver(ChatTestSupport.catalog(tmp), Map.of());
        assertEquals("Java", resolver.resolve("java").orElseThrow().name());
        assertEquals("Java", resolver.resolve("{java}").orElseThrow().name());
        assertEquals("Java", resolver.resolve("{.java}").orElseThrow().name());
        assertEquals("JavaScript", resolver.resolve("js").orElseThrow().name());
        assertEquals("SH", resolver.resolve("bash").orElseThrow().name());
        assertEquals("SH", resolver.resolve("sh").orElseThrow().name());
        assertEquals("Markdown", resolver.resolve("md").orElseThrow().name());
        assertTrue(resolver.resolve("yml").isPresent());
        assertTrue(resolver.resolve("py").isPresent());
        // the file name test: x.<tag>
        assertTrue(resolver.resolve("json").isPresent());
    }

    @Test
    void unknownTagsGiveNothing() {
        var resolver = new LanguageResolver(ChatTestSupport.catalog(tmp), Map.of());
        for (var tag : new String[] {"text", "console", "output", "", "nosuchlang"}) {
            assertTrue(resolver.resolve(tag).isEmpty(), tag);
        }
    }

    @Test
    void configAliasesAddTags() {
        var resolver = new LanguageResolver(ChatTestSupport.catalog(tmp), Map.of("jv", "java", "Weird", "json"));
        assertEquals("Java", resolver.resolve("jv").orElseThrow().name());
        assertTrue(resolver.resolve("weird").isPresent());
    }

    @Test
    void filesResolveByNameAndLlmsTxtIsMarkdown() {
        var resolver = new LanguageResolver(ChatTestSupport.catalog(tmp), Map.of());
        assertEquals("Java", resolver.forFile("src/Main.java").orElseThrow().name());
        assertEquals("Markdown", resolver.forFile("docs/llms.txt").orElseThrow().name());
        assertEquals("Markdown", resolver.forFile("README.md").orElseThrow().name());
        assertTrue(resolver.forFile("notes.unknownext").isEmpty());
        assertTrue(resolver.forFile(null).isEmpty());
    }
}
