package org.github.lscoughlin.jeorje.ui.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputCompletionTest {

    @TempDir
    Path project;

    private InputCompletion completion;

    @BeforeEach
    void setUp() throws Exception {
        FakeBackend.write(project.resolve(".agents/skills/deploy-check/SKILL.md"),
                "---\nname: deploy-check\ndescription: Checks a deploy.\n---\nBody\n");
        FakeBackend.write(project.resolve(".agents/commands/shipit.md"), "---\ndescription: Ship it\n---\nBody\n");
        FakeBackend.write(project.resolve("alpha.txt"), "a");
        FakeBackend.write(project.resolve("src/Main.java"), "x");
        FakeBackend.write(project.resolve("src/Mini.java"), "x");
        FakeBackend.write(project.resolve(".hidden"), "x");
        Files.createDirectories(project.resolve("docs"));
        var slash = new SlashCommands(new FakeBackend(project, Set.of()));
        slash.register("edit", "/edit <path>", "Open a file in the editor", a -> CommandResult.ok(""));
        slash.register("files", "/files [list [filter]]", "Open the file browser, or print the tree", a -> CommandResult.ok(""));
        slash.register("implement", "/implement [plan]", "Implement", a -> CommandResult.ok(""));
        completion = new InputCompletion(slash, project);
    }

    @Test
    void aUniqueCommandNameCompletesWithASpace() {
        assertEquals("/help ", completion.complete("/he").line());
        assertTrue(completion.complete("/he").shown().isEmpty());
    }

    @Test
    void skillsAndCustomCommandsComplete() {
        assertEquals("/deploy-check ", completion.complete("/dep").line());
        assertEquals("/shipit ", completion.complete("/shi").line());
    }

    @Test
    void severalCommandsGiveTheCommonPrefixAndTheList() {
        var result = completion.complete("/co");
        assertEquals("/co", result.line(), "/context and /com... share only /co");
        assertTrue(result.shown().stream().anyMatch(s -> s.startsWith("/compact")), result.shown().toString());
        assertTrue(result.shown().stream().anyMatch(s -> s.startsWith("/commands")), result.shown().toString());
        assertTrue(result.shown().stream().anyMatch(s -> s.startsWith("/context")), result.shown().toString());
        var narrow = completion.complete("/com");
        assertEquals("/com", narrow.line());
        assertTrue(narrow.shown().stream().noneMatch(s -> s.startsWith("/context")), narrow.shown().toString());
    }

    @Test
    void aFixedArgumentCompletes() {
        assertEquals("/trust revoke ", completion.complete("/trust re").line());
    }

    @Test
    void editCompletesFilesAndKeepsDirectoriesOpen() {
        assertEquals("/edit alpha.txt ", completion.complete("/edit al").line());
        assertEquals("/edit src/", completion.complete("/edit sr").line());
        assertEquals("/edit src/M", completion.complete("/edit src/").line());
        assertEquals(2, completion.complete("/edit src/").shown().size());
        assertEquals("/edit src/Main.java ", completion.complete("/edit src/Ma").line());
    }

    @Test
    void hiddenFilesShowOnlyForADotPrefix() {
        assertEquals("/edit ", completion.complete("/edit ").line().substring(0, 6));
        assertTrue(completion.complete("/edit ").shown().stream().noneMatch(s -> s.startsWith(".")));
        assertEquals("/edit .hidden ", completion.complete("/edit .h").line());
    }

    @Test
    void filesCompletesTheListWordAndThePathsAfterIt() {
        assertEquals("/files list ", completion.complete("/files l").line());
        assertEquals("/files x", completion.complete("/files x").line());
        assertEquals("/files list alpha.txt ", completion.complete("/files list al").line());
        assertEquals("/files list src/", completion.complete("/files list sr").line());
    }

    @Test
    void pathsThatGitignoreListsStillComplete() throws Exception {
        FakeBackend.write(project.resolve(".gitignore"), "build/\n*.log\n");
        FakeBackend.write(project.resolve("build/out.class"), "x");
        FakeBackend.write(project.resolve("debug.log"), "x");
        assertEquals("/edit debug.log ", completion.complete("/edit de").line());
        assertEquals("/edit build/", completion.complete("/edit bu").line());
        assertEquals("/edit build/out.class ", completion.complete("/edit build/o").line());
    }

    @Test
    void implementCompletesTheFirstArgumentOnly() {
        assertEquals("/implement docs/", completion.complete("/implement do").line());
        assertEquals("/implement docs/ x", completion.complete("/implement docs/ x").line());
    }

    @Test
    void noMatchAndPlainTextStayUnchanged() {
        assertEquals("/zzz", completion.complete("/zzz").line());
        assertEquals("/edit nothing", completion.complete("/edit nothing").line());
        assertEquals("hello", completion.complete("hello").line());
        assertTrue(completion.complete("hello").shown().isEmpty());
    }
}
