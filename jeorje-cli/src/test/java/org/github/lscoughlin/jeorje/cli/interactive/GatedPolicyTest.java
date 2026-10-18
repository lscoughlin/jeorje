package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.AgentEvent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.github.lscoughlin.jeorje.ui.screen.Screen;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GatedPolicyTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ToolUse use(String tool, String command) {
        var input = MAPPER.createObjectNode();
        if (command != null) input.put("command", command);
        return new ToolUse("t1", tool, input);
    }

    @Test
    void planModeDeniesTheToolsThatPlanModeDoesNotAllow() {
        var plan = new PlanMode();
        var policy = new GatedPolicy(u -> Decision.ALLOW, plan, null);
        assertEquals(Decision.ALLOW, policy.check(use("write", null)), "plan mode is off");
        assertEquals(Decision.DENY, policy.check(use("plan_submit", null)), "plan_submit needs plan mode");
        plan.enter("goal");
        assertEquals(Decision.DENY, policy.check(use("write", null)));
        assertEquals(Decision.DENY, policy.check(use("spawn_agent", null)));
        assertEquals(Decision.ALLOW, policy.check(use("read", null)));
        assertEquals(Decision.ALLOW, policy.check(use("plan_submit", null)));
        assertEquals(Decision.ALLOW, policy.check(use("bash", "ls -la")));
        assertEquals(Decision.DENY, policy.check(use("bash", "rm -rf build")));
        plan.done();
        assertEquals(Decision.ALLOW, policy.check(use("write", null)));
    }

    @Test
    void workerAskBlocksUntilTheUserAnswers() throws Exception {
        var posted = new ArrayList<AgentEvent>();
        var gate = new PromptGate(posted::add);
        var policy = new GatedPolicy(u -> Decision.ASK, null, (agent, use) ->
                gate.ask(agent, use).approved() ? Decision.ALLOW : Decision.DENY);
        var answer = new CompletableFuture<Decision>();
        Thread.ofVirtual().name("subagent-sub-9").start(() -> answer.complete(policy.check(use("write", null))));
        for (var i = 0; i < 500 && posted.isEmpty(); i++) Thread.sleep(10);
        assertEquals(1, posted.size());
        var request = (AgentEvent.ConfirmationRequested) posted.getFirst();
        assertEquals(new AgentId("sub-9"), request.agent());
        assertFalse(answer.isDone(), "the worker waits");
        assertTrue(gate.complete(request.agent(), request.toolUse(), org.github.lscoughlin.jeorje.ui.screen.Decision.ALLOW_ONCE));
        assertEquals(Decision.ALLOW, answer.get(5, TimeUnit.SECONDS));
    }

    @Test
    void denyAllReleasesWaitingWorkersWithDeny() throws Exception {
        var gate = new PromptGate(e -> { });
        var policy = new GatedPolicy(u -> Decision.ASK, null, (agent, use) ->
                gate.ask(agent, use).approved() ? Decision.ALLOW : Decision.DENY);
        var answer = new CompletableFuture<Decision>();
        Thread.ofVirtual().name("subagent-sub-1").start(() -> answer.complete(policy.check(use("bash", "make"))));
        Thread.sleep(100);
        gate.denyAll();
        assertEquals(Decision.DENY, answer.get(5, TimeUnit.SECONDS));
    }

    @Test
    void internalStoryToolsNeverAsk() {
        var asked = new ArrayList<String>();
        var policy = new GatedPolicy(u -> Decision.ASK, null, (agent, use) -> {
            asked.add(use.tool());
            return Decision.ALLOW;
        });
        assertEquals(Decision.ASK, policy.check(use("story_done", null)));
        assertTrue(asked.isEmpty());
        assertEquals(List.of(), asked);
        assertEquals(Decision.ALLOW, policy.check(use("write", null)));
        assertEquals(List.of("write"), asked);
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP = Screen.class;
}
