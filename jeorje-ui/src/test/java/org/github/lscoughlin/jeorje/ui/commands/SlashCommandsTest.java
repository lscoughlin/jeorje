package org.github.lscoughlin.jeorje.ui.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.github.lscoughlin.jeorje.core.instructions.InstructionsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlashCommandsTest {

    @TempDir Path project;
    FakeBackend backend;
    SlashCommands commands;

    @BeforeEach
    void setUp() throws IOException {
        // Reserve the names first so that the custom command scan can skip them.
        var probe = new SlashCommands(new FakeBackend(project, java.util.Set.of()));
        var names = probe.builtInNames();
        FakeBackend.write(project.resolve(".agents/commands/review.md"), """
                ---
                description: Review the changes
                argument-hint: "[focus]"
                model: vendor/strong
                ---

                Review. Focus: $ARGUMENTS. First=$1 Second=$2
                """);
        FakeBackend.write(project.resolve(".agents/commands/model.md"), "---\ndescription: bad\n---\nx");
        FakeBackend.write(project.resolve(".agents/commands/team/ship.md"), "Ship $1");
        FakeBackend.write(project.resolve(".agents/skills/pdf/SKILL.md"),
                "---\nname: pdf\ndescription: Work with PDFs\n---\nRead the PDF carefully.\n");
        FakeBackend.write(project.resolve(".agents/skills/help/SKILL.md"),
                "---\nname: help\ndescription: Clashing skill\n---\nBody\n");
        FakeBackend.write(project.resolve(".agents/skills/review/SKILL.md"),
                "---\nname: review\ndescription: Skill with command name\n---\nBody\n");
        FakeBackend.write(project.resolve(".agents/agents/checker.md"),
                "---\nname: checker\ndescription: Checks things\nmodel: vendor/x\n---\nBody\n");
        backend = new FakeBackend(project, names);
        commands = new SlashCommands(backend);
    }

    @Test
    void parsesNameAndArguments() {
        var p = ParsedCommand.parse("  /model   vendor/x  y ").orElseThrow();
        assertEquals("model", p.name());
        assertEquals("vendor/x  y", p.arguments());
        assertEquals("", ParsedCommand.parse("/help").orElseThrow().arguments());
        assertEquals("team:ship", ParsedCommand.parse("/team:ship a").orElseThrow().name());
    }

    @Test
    void ignoresLinesThatAreNotCommands() {
        assertTrue(ParsedCommand.parse("hello").isEmpty());
        assertTrue(ParsedCommand.parse("/").isEmpty());
        assertTrue(ParsedCommand.parse("/usr/bin/ls -l").isEmpty());
        assertTrue(ParsedCommand.parse("// comment").isEmpty());
        assertTrue(ParsedCommand.parse(null).isEmpty());
        assertFalse(commands.execute("just text").handled());
        assertFalse(commands.execute("/usr/bin/ls").handled());
    }

    @Test
    void unknownCommandIsHandledAsError() {
        var r = commands.execute("/nope");
        assertTrue(r.handled());
        assertTrue(r.error());
        assertTrue(r.output().contains("/nope"));
        assertTrue(backend.prompts.isEmpty());
    }

    @Test
    void contextShowsTheReportOfTheBackendAndTakesNoArguments() {
        var result = commands.execute("/context");
        assertTrue(result.handled() && !result.error());
        assertEquals("Context report.", result.output());
        assertTrue(backend.calls.contains("context"));
        var bad = commands.execute("/context now");
        assertTrue(bad.error(), bad.output());
        assertEquals("Usage: /context", bad.output());
    }

    @Test
    void completesNames() {
        var all = commands.complete("/").stream().map(Candidate::text).toList();
        assertTrue(all.containsAll(List.of("/help", "/model", "/quit", "/review", "/pdf", "/team:ship")));
        var co = commands.complete("/co").stream().map(Candidate::text).toList();
        assertEquals(List.of("/commands", "/compact", "/compactor", "/context"), co);
        assertEquals(List.of("/clear"), commands.complete("/cl").stream().map(Candidate::text).toList());
        var review = commands.complete("/rev").getFirst();
        assertEquals("/review", review.text());
        assertTrue(review.description().contains("Review the changes"));
        assertTrue(review.description().contains("[focus]"));
        assertTrue(commands.complete("text").isEmpty());
        assertTrue(commands.complete("/zzz").isEmpty());
    }

    @Test
    void completesArguments() {
        assertEquals("/trust revoke", commands.complete("/trust r").getFirst().text());
        assertEquals("/advisor none", commands.complete("/advisor ").getFirst().text());
        assertTrue(commands.complete("/model ").isEmpty());
    }

    @Test
    void completionDoesNotOfferHiddenNames() {
        var helps = commands.complete("/help").stream().map(Candidate::text).toList();
        assertEquals(List.of("/help"), helps);
        assertEquals(1, commands.complete("/model").stream().filter(c -> c.text().equals("/model")).count());
    }

    @Test
    void helpListsEverything() {
        var out = commands.execute("/help").output();
        for (var name : List.of("/help", "/model", "/compactor", "/advisor", "/compact", "/context", "/pin", "/clear", "/recall",
                "/keys", "/init", "/instructions", "/skills", "/commands", "/agent-types", "/trust", "/cancel",
                "/back", "/quit")) {
            assertTrue(out.contains(name), name);
        }
        assertTrue(out.contains("/review [focus]"));
        assertTrue(out.contains("/pdf"));
        assertTrue(out.contains("Warnings"));
    }

    @Test
    void modelShowsAndSets() {
        assertTrue(commands.execute("/model").output().contains("vendor/default"));
        var r = commands.execute("/model vendor/new");
        assertFalse(r.error());
        assertEquals("vendor/new", backend.roles.defaultModel());
        assertEquals("vendor/fast", backend.roles.compactor());
        assertEquals(1, backend.applied.size());
        assertTrue(commands.execute("/model off").error());
        assertTrue(commands.execute("/model a b").error());
        assertEquals(1, backend.applied.size());
    }

    @Test
    void compactorAndAdvisorSetAndTurnOff() {
        commands.execute("/advisor");
        assertEquals("~deepseek/deepseek-pro-latest", backend.roles.advisor());
        commands.execute("/advisor vendor/smart");
        assertEquals("vendor/smart", backend.roles.advisor());
        commands.execute("/advisor");
        assertEquals("~deepseek/deepseek-pro-latest", backend.roles.advisor());
        commands.execute("/advisor none");
        assertNull(backend.roles.advisor());
        commands.execute("/compactor off");
        assertNull(backend.roles.compactor());
        assertEquals("vendor/default", backend.roles.defaultModel());
        commands.execute("/compactor vendor/tiny");
        assertEquals("vendor/tiny", backend.roles.compactor());
    }

    @Test
    void memoryCommands() {
        assertEquals("Compacted.", commands.execute("/compact").output());
        assertEquals("Pinned.", commands.execute("/pin").output());
        assertTrue(commands.execute("/recall").error());
        assertTrue(commands.execute("/recall build error").output().contains("No hits"));
        backend.hits = List.of("c1 turn 3 boom");
        assertEquals("c1 turn 3 boom", commands.execute("/recall build error").output());
        assertEquals(List.of("compact", "pin", "recall:build error", "recall:build error"), backend.calls);
    }

    @Test
    void clearStartsAFreshConversation() {
        assertEquals("Cleared.", commands.execute("/clear").output());
        assertTrue(commands.execute("/clear now").error());
        assertEquals(List.of("clear"), backend.calls);
        assertTrue(backend.prompts.isEmpty());
    }

    @Test
    void keysShowsBindings() {
        var out = commands.execute("/keys").output();
        assertFalse(out.isBlank());
        assertTrue(out.contains("ui.keys"));
    }

    @Test
    void initSendsPrompt() {
        var r = commands.execute("/init");
        assertFalse(r.error());
        assertEquals(1, backend.prompts.size());
        assertTrue(backend.prompts.getFirst().text().contains("AGENTS.md"));
        assertNull(backend.prompts.getFirst().model());
    }

    @Test
    void instructionsList() {
        assertTrue(commands.execute("/instructions").output().contains("No AGENTS.md"));
        backend.instructions = List.of(new InstructionsLoader.Instruction(
                InstructionsLoader.Level.PROJECT, Path.of("/p/AGENTS.md"), "x", true));
        var out = commands.execute("/instructions").output();
        assertTrue(out.contains("project"));
        assertTrue(out.contains("AGENTS.md"));
        assertTrue(out.contains("truncated"));
    }

    @Test
    void listingCommands() {
        var skills = commands.execute("/skills").output();
        assertTrue(skills.contains("pdf"));
        assertTrue(skills.contains("Work with PDFs"));
        var custom = commands.execute("/commands").output();
        assertTrue(custom.contains("/review [focus]"));
        assertTrue(custom.contains("vendor/strong"));
        assertTrue(custom.contains("built-in"), "diagnostic for the skipped command");
        var types = commands.execute("/agent-types").output();
        assertTrue(types.contains("checker"));
        assertTrue(types.contains("vendor/x"));
    }

    @Test
    void trustAndRevoke() {
        assertTrue(commands.execute("/trust").output().contains("now trusted"));
        assertTrue(backend.trusted);
        assertTrue(commands.execute("/trust").output().contains("already"));
        commands.execute("/trust revoke");
        assertFalse(backend.trusted);
        assertTrue(commands.execute("/trust bogus").error());
        assertEquals(List.of("trust", "revoke"), backend.calls);
    }

    @Test
    void cancelBackQuit() {
        commands.execute("/cancel");
        commands.execute("/cancel worker-1");
        commands.execute("/back");
        assertEquals(List.of("cancel:null", "cancel:worker-1", "back"), backend.calls);
        assertFalse(backend.quit);
        commands.execute("/quit");
        assertTrue(backend.quit);
    }

    @Test
    void customCommandExpandsArgumentsAndUsesModel() {
        commands.execute("/review \"the parser\" fast");
        var p = backend.prompts.getFirst();
        assertEquals("vendor/strong", p.model());
        assertTrue(p.text().contains("Focus: \"the parser\" fast."));
        assertTrue(p.text().contains("First=the parser"));
        assertTrue(p.text().contains("Second=fast"));
    }

    @Test
    void customCommandInSubfolderAndMissingArgument() {
        commands.execute("/team:ship");
        assertEquals("Ship", backend.prompts.getFirst().text().strip());
        assertNull(backend.prompts.getFirst().model());
        commands.execute("/team:ship v2");
        assertEquals("Ship v2", backend.prompts.get(1).text());
    }

    @Test
    void skillInvocationSendsBodyAndArguments() {
        var r = commands.execute("/pdf report.pdf");
        assertFalse(r.error());
        var text = backend.prompts.getFirst().text();
        assertTrue(text.contains("skill 'pdf'"));
        assertTrue(text.contains("Read the PDF carefully."));
        assertTrue(text.contains("SKILL.md"));
        assertTrue(text.contains("Arguments: report.pdf"));
    }

    @Test
    void builtInWinsOverSkillsAndCommands() {
        // The skill named help does not run. The built-in help runs.
        var r = commands.execute("/help");
        assertTrue(r.output().contains("Commands"));
        assertTrue(backend.prompts.isEmpty());
        // The custom command named model was skipped by the registry.
        assertTrue(backend.commands.find("model").isEmpty());
        commands.execute("/model vendor/z");
        assertTrue(backend.prompts.isEmpty());
    }

    @Test
    void clashesProduceWarnings() {
        var warnings = String.join("\n", commands.warnings());
        assertTrue(warnings.contains("'help' is a built-in command"), warnings);
        assertTrue(warnings.contains("'model' is a built-in command"), warnings);
        assertTrue(warnings.contains("The skill 'review' is hidden by the custom command"), warnings);
    }

    @Test
    void customCommandWinsOverSkillWithSameName() {
        commands.execute("/review x");
        assertEquals("vendor/strong", backend.prompts.getFirst().model());
    }

    @Test
    void reservedNamesCannotBeShadowed() {
        assertTrue(commands.builtInNames().containsAll(List.of("plan", "implement", "worktrees", "merge",
                "discard", "files", "edit", "agents", "peers")));
        var r = commands.execute("/plan");
        assertTrue(r.error());
        commands.register("plan", "/plan", "Plan mode", a -> CommandResult.ok("planning " + a));
        assertEquals("planning x", commands.execute("/plan x").output());
    }
}
