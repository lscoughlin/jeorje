package org.github.lscoughlin.jeorje.tools.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.compaction.ContextClassifier;
import org.github.lscoughlin.jeorje.core.compaction.Protection;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.permissions.PathSandbox;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SkillToolTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path tmp;

    Skill skill(String name, String body, String compat) throws IOException {
        var dir = Files.createDirectories(tmp.resolve("skills").resolve(name));
        var file = dir.resolve("SKILL.md");
        Files.writeString(file, "---\nname: " + name + "\n---\n" + body);
        return new Skill(name, "Does " + name, file.toAbsolutePath(), null, body, null, compat, Map.of(), null, false);
    }

    ToolContext ctx(String agent) {
        return new ToolContext(tmp, new AgentId(agent), CancellationToken.none(), null, Clock.systemUTC());
    }

    @Test
    void noSkillMeansNoTool() {
        assertTrue(SkillTool.create(List.of()).isEmpty());
        assertTrue(SkillTool.create(null).isEmpty());
    }

    @Test
    void schemaHasEnumOfNames() throws Exception {
        var tool = SkillTool.create(List.of(skill("alpha", "A", null), skill("beta", "B", null))).orElseThrow();
        var schema = tool.definition().inputSchema();
        var values = schema.path("properties").path("name").path("enum");
        assertEquals(2, values.size());
        assertEquals("alpha", values.get(0).asString());
        assertEquals("beta", values.get(1).asString());
        assertEquals("name", schema.path("required").get(0).asString());
        assertTrue(tool.readOnly());
        var registry = ToolRegistry.builder().register(tool).build();
        assertTrue(registry.find("skill").isPresent());
    }

    @Test
    void wrapsResultWithResourcesAndCompatibility() throws Exception {
        var s = skill("alpha", "# Alpha\nDo it.\n", "Needs git");
        Files.createDirectories(s.directory().resolve("scripts"));
        Files.writeString(s.directory().resolve("scripts/log.sh"), "echo");
        var tool = SkillTool.create(List.of(s)).orElseThrow();
        var result = tool.execute(JSON.readTree("{\"name\":\"alpha\"}"), ctx("a1"));
        assertFalse(result.error());
        var out = result.output();
        assertTrue(out.startsWith("<skill_content name=\"alpha\">\n# Alpha\nDo it."), out);
        assertFalse(out.contains("---"));
        assertTrue(out.contains("Compatibility: Needs git"));
        assertTrue(out.contains("Skill directory: " + s.directory()));
        assertTrue(out.contains("<file>scripts/log.sh</file>"));
        assertFalse(out.contains("<file>SKILL.md</file>"));
        assertTrue(out.endsWith("</skill_resources>\n</skill_content>"));
    }

    @Test
    void replacesArguments() throws Exception {
        var tool = SkillTool.create(List.of(skill("alpha", "Run for $ARGUMENTS now. Again $ARGUMENTS.", null))).orElseThrow();
        var out = tool.execute(JSON.readTree("{\"name\":\"alpha\",\"arguments\":\"v1..v2\"}"), ctx("a1")).output();
        assertTrue(out.contains("Run for v1..v2 now. Again v1..v2."));
        assertFalse(out.contains("$ARGUMENTS"));
    }

    @Test
    void missingArgumentsBecomeEmpty() throws Exception {
        var tool = SkillTool.create(List.of(skill("alpha", "Go $ARGUMENTS!", null))).orElseThrow();
        assertTrue(tool.execute(JSON.readTree("{\"name\":\"alpha\"}"), ctx("a1")).output().contains("Go !"));
    }

    @Test
    void unknownNameIsError() throws Exception {
        var tool = SkillTool.create(List.of(skill("alpha", "A", null))).orElseThrow();
        assertTrue(tool.execute(JSON.readTree("{\"name\":\"nope\"}"), ctx("a1")).error());
    }

    @Test
    void secondCallReturnsShortNoteUntilReleased() throws Exception {
        var tool = SkillTool.create(List.of(skill("alpha", "Body text", null))).orElseThrow();
        var input = JSON.readTree("{\"name\":\"alpha\"}");
        assertTrue(tool.execute(input, ctx("a1")).output().contains("Body text"));
        var second = tool.execute(input, ctx("a1"));
        assertFalse(second.error());
        assertFalse(second.output().contains("Body text"));
        assertTrue(second.output().contains("already in your context"));
        // Another agent has its own context.
        assertTrue(tool.execute(input, ctx("a2")).output().contains("Body text"));
        tool.release("a1", "alpha");
        assertTrue(tool.execute(input, ctx("a1")).output().contains("Body text"));
        tool.releaseAll("a1");
        assertTrue(tool.execute(input, ctx("a1")).output().contains("Body text"));
    }

    @Test
    void resourceListStopsAtHundred() throws Exception {
        var s = skill("big", "B", null);
        for (var i = 0; i < 105; i++) Files.writeString(s.directory().resolve(String.format("f%03d.txt", i)), "x");
        var out = SkillTool.render(s, "");
        assertEquals(100, out.lines().filter(l -> l.contains("<file>")).count());
        assertTrue(out.contains("The list stops at 100 files."));
    }

    @Test
    void sandboxAllowsReadOnlyAccessToSkillFolders() throws Exception {
        var s = skill("alpha", "A", null);
        var workspace = Files.createDirectories(tmp.resolve("ws"));
        var sandbox = new PathSandbox(workspace, List.of(), SkillTool.readableDirectories(List.of(s)), List.of());
        assertEquals(PathSandbox.Area.READ_ONLY, sandbox.check(s.location().toString()).area());
        assertEquals(PathSandbox.Area.OUTSIDE, sandbox.check(tmp.resolve("other.txt").toString()).area());
    }

    @Test
    void classifierProtectsSkillResults() throws Exception {
        var tool = SkillTool.create(List.of(skill("alpha", "A", null))).orElseThrow();
        var text = tool.execute(JSON.readTree("{\"name\":\"alpha\"}"), ctx("a1")).output();
        var chunk = new Chunk("a1:1.0", "s", "a1", 1, 0, Instant.EPOCH, "tool_result", SkillTool.NAME, text);
        var ctxt = ContextClassifier.Context.of(1);
        assertEquals(Protection.PROTECTED, new ContextClassifier().classifyOne(chunk, ctxt));
    }

    @Test
    void onLoadedRunsOnceForTheFirstLoadOnly() throws Exception {
        var loadedNames = new java.util.ArrayList<String>();
        var tool = SkillTool.create(List.of(skill("demo", "body", null)), sk -> loadedNames.add(sk.name())).orElseThrow();
        var input = JSON.readTree("{\"name\":\"demo\"}");
        tool.execute(input, ctx("a"));
        tool.execute(input, ctx("a"));
        assertEquals(List.of("demo"), loadedNames);
        assertEquals(1, tool.skills().size());
    }
}
