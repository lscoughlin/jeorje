package org.github.lscoughlin.jeorje.core.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptInstructionsTest {

    @TempDir
    Path tmp;

    private Skill skill(String name, String description) {
        return new Skill(name, description, tmp.resolve(".agents/skills/" + name + "/SKILL.md"),
                Scopes.Scope.PROJECT_AGENTS, "body", null, null, java.util.Map.of(), null, false);
    }

    @Test
    void projectTextComesAfterUserTextAndCatalogFollows() throws IOException {
        var user = tmp.resolve("user-agents.md");
        Files.writeString(user, "USER-RULE");
        Files.writeString(tmp.resolve("AGENTS.md"), "PROJECT-RULE");
        var loader = new InstructionsLoader(List.of(user), 1000, 10_000);
        var text = new PromptInstructions(loader, tmp, () -> List.of(skill("demo", "Runs the demo.")), 8000).text(tmp);
        assertTrue(text.indexOf("USER-RULE") < text.indexOf("PROJECT-RULE"), text);
        assertTrue(text.indexOf("PROJECT-RULE") < text.indexOf("<available_skills>"), text);
        assertTrue(text.contains("<name>demo</name>"));
        assertTrue(text.contains("<location>" + tmp.resolve(".agents/skills/demo/SKILL.md") + "</location>"));
        assertTrue(text.contains(SkillCatalog.INSTRUCTIONS.lines().findFirst().orElseThrow()));
    }

    @Test
    void noSkillMeansNoCatalog() throws IOException {
        Files.writeString(tmp.resolve("AGENTS.md"), "PROJECT-RULE");
        var loader = new InstructionsLoader(List.of(), 1000, 10_000);
        var text = new PromptInstructions(loader, tmp, List::of, 8000).text(tmp);
        assertEquals("Instructions from " + tmp.resolve("AGENTS.md") + " (project):\nPROJECT-RULE", text);
        assertFalse(text.contains("available_skills"));
        assertEquals("", new PromptInstructions(null, tmp, null, 8000).text(tmp));
    }

    @Test
    void catalogIsCutAtMaxListChars() {
        var skills = List.of(skill("aaa", "first"), skill("bbb", "second"), skill("ccc", "third"));
        var all = new PromptInstructions(null, tmp, () -> skills, 8000).catalog();
        assertTrue(all.contains("<name>ccc</name>"));
        var cut = new PromptInstructions(null, tmp, () -> skills, 600).catalog();
        assertTrue(cut.contains("<name>aaa</name>"));
        assertFalse(cut.contains("<name>ccc</name>"), cut);
        assertTrue(cut.length() <= 600, "length " + cut.length());
    }

    @Test
    void fileCapsApply() throws IOException {
        Files.writeString(tmp.resolve("AGENTS.md"), "x".repeat(500));
        var loader = new InstructionsLoader(List.of(), 100, 10_000);
        var text = new PromptInstructions(loader, tmp, null, 8000).text(tmp);
        assertTrue(text.contains("[This file is cut at 100 bytes.]"), text);
        var tiny = new PromptInstructions(new InstructionsLoader(List.of(), 1000, 120), tmp, null, 8000);
        Files.writeString(tmp.resolve("AGENTS.md"), "y".repeat(500));
        assertTrue(tiny.text(tmp).contains("total size limit"), tiny.text(tmp));
    }

    @Test
    void editsApplyOnTheNextCallThroughTheAgentSettings() throws IOException {
        var file = tmp.resolve("AGENTS.md");
        Files.writeString(file, "VERSION-ONE");
        var loader = new InstructionsLoader(List.of(), 1000, 10_000);
        var source = new PromptInstructions(loader, tmp, null, 8000);
        var settings = AgentSettings.defaults("s", tmp, 1000).withInstructionsSource(source::text);
        assertTrue(settings.instructionsFor(tmp).contains("VERSION-ONE"));
        var reads = loader.diskReads();
        settings.instructionsFor(tmp);
        assertEquals(reads, loader.diskReads(), "an unchanged file is not read again");
        Files.writeString(file, "VERSION-TWO-LONGER");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        assertTrue(settings.instructionsFor(tmp).contains("VERSION-TWO-LONGER"));
        assertFalse(settings.instructionsFor(tmp).contains("VERSION-ONE"));
    }

    @Test
    void missingWorkingDirectoryFallsBackToTheProject() throws IOException {
        Files.writeString(tmp.resolve("AGENTS.md"), "PROJECT-RULE");
        var source = new PromptInstructions(new InstructionsLoader(List.of(), 1000, 10_000), tmp, null, 8000);
        assertTrue(source.text(tmp.resolve("not-yet-made")).contains("PROJECT-RULE"));
    }

    @Test
    void fixedInstructionsStayWithoutASource() {
        var settings = new AgentSettings("s", tmp, 1000, 60, 100, 6, 10, null, null, "FIXED", null);
        assertEquals("FIXED", settings.instructionsFor(tmp));
        assertEquals(AgentSettings.DEFAULT_MAX_PROTECTED_TOKENS, settings.maxProtectedTokens());
        assertEquals(500, settings.withMaxProtectedTokens(500).maxProtectedTokens());
    }
}
