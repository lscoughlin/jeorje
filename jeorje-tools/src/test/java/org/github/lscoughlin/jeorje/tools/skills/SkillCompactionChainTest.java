package org.github.lscoughlin.jeorje.tools.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.compaction.CompactionSettings;
import org.github.lscoughlin.jeorje.core.compaction.ContextCompactor;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.compaction.TermStats;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Chunk;
import org.github.lscoughlin.jeorje.core.model.Content;
import org.github.lscoughlin.jeorje.core.model.ToolResult;
import org.github.lscoughlin.jeorje.testkit.FixedPolicy;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole chain: load a skill, compaction prunes it, the model loads it again. */
class SkillCompactionChainTest {
    @TempDir Path tmp;

    private static final String MARK = "SKILL-BODY-MARKER";

    private Skill skill() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("skills/demo"));
        var file = dir.resolve("SKILL.md");
        var body = (MARK + " some guidance for the demo task.\n").repeat(60);
        Files.writeString(file, "---\nname: demo\n---\n" + body);
        return new Skill("demo", "Does demo", file.toAbsolutePath(), null, body, null, null, Map.of(), null, false);
    }

    private static String toolResultTexts(org.github.lscoughlin.jeorje.core.model.LlmRequest request) {
        var sb = new StringBuilder();
        for (var m : request.messages()) {
            for (Content c : m.content()) if (c instanceof ToolResult r) sb.append(r.output()).append('\n');
        }
        return sb.toString();
    }

    private List<String> run(boolean wired) throws Exception {
        var tool = SkillTool.create(List.of(skill())).orElseThrow();
        var llm = new ScriptedLlmClient(
            Response.toolCall("c1", "skill", "{\"name\":\"demo\"}"), Response.text("loaded"),
            Response.text("two"), Response.text("three"), Response.text("four"),
            Response.toolCall("c2", "skill", "{\"name\":\"demo\"}"), Response.text("again"));
        var d = AgentSettings.defaults("s1", tmp, 100_000);
        var settings = new AgentSettings(d.sessionId(), tmp, 100_000, 60, 8192, 1, 100, d.retry(), null, "", d.sleeper())
            .withMaxProtectedTokens(1);
        var id = new AgentId("a1");
        var agent = new Agent(id, settings, llm, List.of(tool), FixedPolicy.allow(), new InMemoryContextStore(),
            (turn, ts) -> { }, e -> { }, Clock.systemUTC());
        var pruner = new Pruner(null, null, TermStats.UNIFORM, Pruner.Config.defaults());
        agent.withCompactor(new ContextCompactor(pruner,
            new CompactionSettings(1_000, 50, 80, 1, 0, 20, 16_000, 6), id));
        var released = new ArrayList<Chunk>();
        if (wired) {
            agent.withPruneListener(chunks -> {
                released.addAll(chunks);
                tool.releasePruned(id.value(), chunks);
            });
        }
        for (var t = 1; t <= 5; t++) agent.runTurn("turn " + t);
        if (wired) {
            assertTrue(released.stream().anyMatch(c -> c.text().contains("<skill_content name=\"demo\">")),
                "the pruned skill chunk reached the listener");
        }
        var last = llm.requests().getLast();
        var fresh = toolResultTexts(last);
        return List.of(fresh, String.valueOf(llm.callCount()));
    }

    @Test
    void aPrunedSkillCanBeLoadedAgain() throws Exception {
        var result = run(true);
        assertEquals("7", result.get(1));
        assertTrue(result.get(0).contains(MARK), "the second call returned the body again");
    }

    @Test
    void withoutTheListenerTheSkillStaysUnavailable() throws Exception {
        var result = run(false);
        assertEquals("7", result.get(1));
        assertTrue(result.get(0).contains("already in your context"), result.get(0));
    }
}
