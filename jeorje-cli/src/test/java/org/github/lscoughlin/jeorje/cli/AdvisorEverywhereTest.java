package org.github.lscoughlin.jeorje.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.model.Text;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each agent gets a review of its own context, and the tool follows the advisor switch. */
class AdvisorEverywhereTest {
    @TempDir Path dir;

    private static String text(org.github.lscoughlin.jeorje.cli.AdvisorSupport support, AgentId agent) {
        var sb = new StringBuilder();
        for (Message m : support.context(agent).messages()) {
            m.content().forEach(c -> {
                if (c instanceof Text t) sb.append(t.text()).append('\n');
            });
        }
        return sb.toString();
    }

    private Agent agent(String id, String reply) {
        var settings = AgentSettings.defaults("s1", dir, 100_000);
        return new Agent(new AgentId(id), settings, new ScriptedLlmClient(Response.text(reply)), List.of(),
                call -> PermissionPolicy.Decision.ALLOW, new InMemoryContextStore(), null, e -> { }, Clock.systemUTC());
    }

    @Test
    void anAgentGetsOnlyItsOwnContextForTheAdvisor() {
        var support = new AdvisorSupport(() -> true, () -> null);
        var main = agent("adv-main", "ok");
        var sub = agent("adv-sub-1", "ok");
        main.runTurn("alpha question about the plan");
        sub.runTurn("beta task for the subagent");
        var mainText = text(support, new AgentId("adv-main"));
        var subText = text(support, new AgentId("adv-sub-1"));
        assertTrue(mainText.contains("alpha question"), mainText);
        assertFalse(mainText.contains("beta task"), mainText);
        assertTrue(subText.contains("beta task"), subText);
        assertFalse(subText.contains("alpha question"), subText);
        assertTrue(text(support, new AgentId("adv-nobody")).isEmpty(), "an unknown agent has no context");
    }

    @Test
    void theToolIsHiddenFromTheModelOnlyWhileTheAdvisorIsOff() {
        var on = new AtomicBoolean(true);
        var support = new AdvisorSupport(on::get, () -> null);
        var seen = new java.util.ArrayList<List<String>>();
        org.github.lscoughlin.jeorje.core.port.LlmClient base = (request, cancel) -> {
            seen.add(request.tools().stream().map(t -> t.name()).toList());
            return new ScriptedLlmClient(Response.text("ok")).stream(request, cancel);
        };
        var settings = AgentSettings.defaults("s1", dir, 100_000);
        var tool = support.slot();
        var agent = new Agent(new AgentId("adv-hide"), settings, support.tap(base), List.of(tool),
                call -> PermissionPolicy.Decision.ALLOW, new InMemoryContextStore(), null, e -> { }, Clock.systemUTC());
        agent.runTurn("one");
        on.set(false);
        agent.runTurn("two");
        on.set(true);
        agent.runTurn("three");
        assertEquals(List.of(List.of("advisor"), List.<String>of(), List.of("advisor")), seen);
    }
}
