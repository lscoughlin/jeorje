package org.github.lscoughlin.jeorje.ui.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntaxCatalogTest {

    @TempDir
    Path tmp;

    private SyntaxSettings embeddedOnly(String version) {
        return new SyntaxSettings(null, null, List.of(), List.of(), true, tmp.resolve("cache"), version);
    }

    private Path write(Path dir, String file, String content) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(file), content);
    }

    private static String syntaxFile(String name, String pattern) {
        return "syntax \"" + name + "\" \"" + pattern + "\"\ncolor red \"foo\"\n";
    }

    @Test
    void searchOrderAndOverrideRule() throws IOException {
        var project = tmp.resolve("project");
        var user = tmp.resolve("user");
        var config = tmp.resolve("config");
        var system = tmp.resolve("system");
        write(project, "a.nanorc", syntaxFile("Java", "\\.java$"));
        write(user, "a.nanorc", syntaxFile("Java", "\\.jv$"));
        write(user, "b.nanorc", syntaxFile("UserOnly", "\\.uo$"));
        write(config, "a.nanorc", syntaxFile("ConfigOnly", "\\.co$"));
        write(system, "a.nanorc", syntaxFile("SysOnly", "\\.so$"));
        var settings = new SyntaxSettings(project, user, List.of(config), List.of(system), true,
                tmp.resolve("cache"), "t1");
        var catalog = SyntaxCatalog.load(settings);

        var java = catalog.byName("java").orElseThrow();
        assertEquals(project.resolve("a.nanorc"), java.file(), "project overrides user and embedded");
        assertTrue(catalog.forFileName("x.jv").isEmpty() || !catalog.forFileName("x.jv").get().name().equals("Java"));
        assertEquals("UserOnly", catalog.forFileName("x.uo").orElseThrow().name());
        assertEquals("ConfigOnly", catalog.forFileName("x.co").orElseThrow().name());
        assertEquals("SysOnly", catalog.forFileName("x.so").orElseThrow().name());
        assertEquals("Java", catalog.forFileName("A.java").orElseThrow().name());
        assertTrue(catalog.byName("python").isPresent(), "the embedded set is the last step");
        assertEquals(List.of(project, user, config, system), catalog.searchedDirs().subList(0, 4));
    }

    @Test
    void embeddedStepCanBeOff() {
        var catalog = SyntaxCatalog.load(embeddedOnly("v").withEmbedded(false));
        assertTrue(catalog.syntaxes().isEmpty());
    }

    @Test
    void firstStartExtractsAndReuses() throws IOException {
        var first = SyntaxCatalog.load(embeddedOnly("1.0"));
        var dir = tmp.resolve("cache/1.0");
        assertTrue(Files.isDirectory(dir));
        assertTrue(Files.exists(dir.resolve("java.nanorc")));
        assertEquals(List.of(dir), first.searchedDirs());
        // Reuse: a marker file stays, because Jeorje does not write again.
        Files.writeString(dir.resolve("marker.txt"), "x");
        var second = SyntaxCatalog.load(embeddedOnly("1.0"));
        assertTrue(Files.exists(dir.resolve("marker.txt")));
        assertEquals(first.syntaxes().size(), second.syntaxes().size());
    }

    @Test
    void interruptedWriteLeavesNoPartialSet() throws IOException {
        var cache = tmp.resolve("cache");
        var partial = Files.createDirectories(cache.resolve(".tmp-1.0-abc"));
        Files.writeString(partial.resolve("java.nanorc"), "syntax \"Broken\" \"x\"\n");
        Files.setLastModifiedTime(partial, FileTime.from(Instant.now().minusSeconds(7200)));
        var catalog = SyntaxCatalog.load(embeddedOnly("1.0"));
        assertTrue(catalog.byName("broken").isEmpty());
        assertTrue(catalog.byName("java").isPresent());
        assertFalse(Files.exists(partial), "a stale temporary directory is removed");
        assertTrue(Files.isDirectory(cache.resolve("1.0")));
    }

    @Test
    void youngTemporaryDirectoryStaysAndIsNeverUsed() throws IOException {
        var cache = tmp.resolve("cache");
        var young = Files.createDirectories(cache.resolve(".tmp-1.0-live"));
        var catalog = SyntaxCatalog.load(embeddedOnly("1.0"));
        assertTrue(Files.exists(young));
        assertFalse(catalog.searchedDirs().contains(young));
    }

    @Test
    void newVersionWritesNewDirectoryAndRemovesOld() {
        SyntaxCatalog.load(embeddedOnly("1.0"));
        assertTrue(Files.isDirectory(tmp.resolve("cache/1.0")));
        SyntaxCatalog.load(embeddedOnly("2.0"));
        assertTrue(Files.isDirectory(tmp.resolve("cache/2.0")));
        assertFalse(Files.exists(tmp.resolve("cache/1.0")));
    }

    @Test
    void brokenFilesAreSkippedWithWarning() throws IOException {
        var dir = tmp.resolve("dir");
        write(dir, "empty.nanorc", "# nothing here\n");
        write(dir, "badpattern.nanorc", "syntax \"Bad\" \"(unclosed\" \"\\.ok$\"\n");
        write(dir, "good.nanorc", syntaxFile("Good", "\\.good$"));
        var catalog = SyntaxCatalog.load(embeddedOnly("v").withEmbedded(false).withConfigDirs(List.of(dir)));
        assertEquals("Good", catalog.forFileName("a.good").orElseThrow().name());
        assertEquals("Bad", catalog.forFileName("a.ok").orElseThrow().name());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("empty.nanorc")));
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("Bad file name pattern")));
    }

    @Test
    void headerAndMagicWarnOncePerFile() throws IOException {
        var dir = tmp.resolve("dir");
        write(dir, "h.nanorc", "syntax \"H\" \"\\.h1$\"\nheader \"^#!/x\"\nmagic \"text\"\nheader \"y\"\n");
        var catalog = SyntaxCatalog.load(embeddedOnly("v").withEmbedded(false).withConfigDirs(List.of(dir)));
        var count = catalog.warnings().stream().filter(w -> w.contains("h.nanorc") && w.contains("header")).count();
        assertEquals(1, count);
        assertEquals("H", catalog.forFileName("q.h1").orElseThrow().name());
    }

    @Test
    void unquotedNamesAndPosixClasses() throws IOException {
        var dir = tmp.resolve("dir");
        write(dir, "g.nanorc", "syntax gemini \"\\.(gemini|gmi)$\"\n");
        write(dir, "n.nanorc", "syntax \"Num\" \"n[[:digit:]]+\\.num$\"\n");
        var catalog = SyntaxCatalog.load(embeddedOnly("v").withEmbedded(false).withConfigDirs(List.of(dir)));
        assertEquals("gemini", catalog.forFileName("a.gmi").orElseThrow().name());
        assertEquals("Num", catalog.forFileName("n42.num").orElseThrow().name());
    }

    /** Language name, sample file name, and expected syntax name (case-insensitive). */
    private static final Map<String, String[]> LANGUAGES = Map.ofEntries(
            Map.entry("Java", new String[] {"Foo.java", "java"}),
            Map.entry("Kotlin", new String[] {"Foo.kt", "kotlin"}),
            Map.entry("Scala", new String[] {"Foo.scala", "scala"}),
            Map.entry("Groovy", new String[] {"Foo.groovy", "groovy"}),
            Map.entry("Gradle", new String[] {"build.gradle", "groovy"}),
            Map.entry("properties", new String[] {"app.properties", "properties"}),
            Map.entry("C", new String[] {"main.c", "c"}),
            Map.entry("C++", new String[] {"main.cpp", "c"}),
            Map.entry("C#", new String[] {"Main.cs", "c#"}),
            Map.entry("Go", new String[] {"main.go", "go"}),
            Map.entry("Rust", new String[] {"main.rs", "rust"}),
            Map.entry("Swift", new String[] {"main.swift", "swift"}),
            Map.entry("Zig", new String[] {"main.zig", "zig"}),
            Map.entry("Python", new String[] {"main.py", "python"}),
            Map.entry("Ruby", new String[] {"main.rb", "ruby"}),
            Map.entry("PHP", new String[] {"index.php", "php"}),
            Map.entry("Perl", new String[] {"main.pl", "perl"}),
            Map.entry("Lua", new String[] {"main.lua", "lua"}),
            Map.entry("Tcl", new String[] {"main.tcl", "tcl"}),
            Map.entry("JavaScript", new String[] {"main.js", "javascript"}),
            Map.entry("TypeScript", new String[] {"main.ts", "javascript|typescript"}),
            Map.entry("JSON", new String[] {"data.json", "json"}),
            Map.entry("HTML", new String[] {"index.html", "html"}),
            Map.entry("CSS", new String[] {"style.css", "css"}),
            Map.entry("SCSS", new String[] {"style.scss", "css"}),
            Map.entry("XML", new String[] {"pom.xml", "xml"}),
            Map.entry("YAML", new String[] {"a.yml", "yaml"}),
            Map.entry("TOML", new String[] {"a.toml", "toml"}),
            Map.entry("INI", new String[] {"a.ini", "ini"}),
            Map.entry("SQL", new String[] {"a.sql", "sql"}),
            Map.entry("Dockerfile", new String[] {"Dockerfile", "dockerfile"}),
            Map.entry("Terraform", new String[] {"main.tf", "hcl"}),
            Map.entry("HCL", new String[] {"a.hcl", "hcl"}),
            Map.entry("nanorc", new String[] {".nanorc", "nanorc"}),
            Map.entry("git config", new String[] {".gitconfig", "git-config"}),
            Map.entry("sh", new String[] {"run.sh", "sh"}),
            Map.entry("bash", new String[] {".bashrc", "sh"}),
            Map.entry("zsh", new String[] {"a.zsh", "zsh"}),
            Map.entry("fish", new String[] {"a.fish", "fish"}),
            Map.entry("PowerShell", new String[] {"a.ps1", "powershell"}),
            Map.entry("Makefile", new String[] {"Makefile", "makefile"}),
            Map.entry("CMake", new String[] {"CMakeLists.txt", "cmake"}),
            Map.entry("Haskell", new String[] {"a.hs", "haskell"}),
            Map.entry("OCaml", new String[] {"a.ml", "ocaml"}),
            Map.entry("Elixir", new String[] {"a.ex", "elixir"}),
            Map.entry("Clojure", new String[] {"a.clj", "clojure"}),
            Map.entry("Lisp", new String[] {"a.lisp", "lisp"}),
            Map.entry("assembly", new String[] {"a.asm", "asm"}),
            Map.entry("Fortran", new String[] {"a.f90", "fortran"}),
            Map.entry("Markdown", new String[] {"README.md", "markdown"}),
            Map.entry("LaTeX", new String[] {"a.tex", "tex"}),
            Map.entry("groff", new String[] {"a.ms", "groff"}),
            Map.entry("man", new String[] {"a.1", "man"}),
            Map.entry("diff", new String[] {"a.patch", "patch"}),
            Map.entry("email", new String[] {"a.eml", "email"}),
            Map.entry("Vim script", new String[] {"a.vim", "vi"}),
            Map.entry("Objective-C", new String[] {"Foo.m", "objective-c"}),
            Map.entry("Dart", new String[] {"main.dart", "dart"}),
            Map.entry("R", new String[] {"analysis.R", "r"}),
            Map.entry("Julia", new String[] {"main.jl", "julia"}),
            Map.entry("GraphQL", new String[] {"schema.graphql", "graphql"}),
            Map.entry("Nix", new String[] {"default.nix", "nix"}),
            Map.entry("protobuf", new String[] {"api.proto", "protobuf"}),
            Map.entry("Erlang", new String[] {"main.erl", "erlang"}),
            Map.entry("Guile", new String[] {"init.guile", "guile"}),
            Map.entry("Pascal", new String[] {"main.pas", "pascal"}),
            Map.entry("ChangeLog", new String[] {"ChangeLog", "changelog"}),
            Map.entry("log files", new String[] {"server.log", "log"}));

    @Test
    void fileNameSelectionForListedLanguages() {
        var catalog = SyntaxCatalog.load(embeddedOnly("sel"));
        for (var entry : LANGUAGES.entrySet()) {
            var sample = entry.getValue()[0];
            var expected = List.of(entry.getValue()[1].split("\\|"));
            var found = catalog.forFileName(sample);
            assertTrue(found.isPresent(), entry.getKey() + ": no syntax for " + sample);
            assertTrue(expected.contains(found.get().name().toLowerCase()),
                    entry.getKey() + ": " + sample + " gave " + found.get().name());
        }
    }

    @Test
    void pathsSelectByBaseNameAndUnknownFilesGivePlainText() {
        var catalog = SyntaxCatalog.load(embeddedOnly("sel2"));
        assertEquals("Java", catalog.forFileName("/work/src/Foo.java").orElseThrow().name());
        assertEquals("Java", catalog.forFileName("C:\\work\\Foo.java").orElseThrow().name());
        assertTrue(catalog.forFileName("notes.unknownext").isEmpty());
        assertTrue(catalog.forFileName("").isEmpty());
        assertTrue(catalog.forFileName(null).isEmpty());
    }

    @Test
    void endAnchoredMatchBeatsInnerMatch() throws IOException {
        var dir = tmp.resolve("dir");
        write(dir, "a.nanorc", "syntax \"Loose\" \"x\"\n");
        write(dir, "b.nanorc", "syntax \"Exact\" \"\\.abc$\"\n");
        var catalog = SyntaxCatalog.load(embeddedOnly("v").withEmbedded(false).withConfigDirs(List.of(dir)));
        assertEquals("Exact", catalog.forFileName("x.abc").orElseThrow().name());
        assertEquals("Loose", catalog.forFileName("xyz").orElseThrow().name());
    }

    @Test
    void embeddedFilesHaveSourcesAndHeaders() {
        assertNotNull(SyntaxCatalog.class.getResource(SyntaxCatalog.RESOURCE_DIR + "/SOURCES.md"));
        assertNotNull(SyntaxCatalog.class.getResource(SyntaxCatalog.RESOURCE_DIR + "/java.nanorc"));
    }
}
