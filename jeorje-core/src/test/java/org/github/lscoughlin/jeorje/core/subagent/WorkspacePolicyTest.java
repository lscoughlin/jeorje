package org.github.lscoughlin.jeorje.core.subagent;

import static org.github.lscoughlin.jeorje.core.subagent.SubagentTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A subagent with a worktree gets the policy of the worktree. Another subagent gets the policy of its parent. */
class WorkspacePolicyTest {
    @TempDir Path dir;

    @Test
    void worktreeSubagentGetsTheDerivedPolicyAndSharedSubagentGetsTheParentPolicy() throws Exception {
        var derived = new ArrayList<Path>();
        var settings = AgentSettings.defaults("s1", dir, 100_000).withSleeper(d -> { });
        var models = new java.util.concurrent.ConcurrentHashMap<String, org.github.lscoughlin.jeorje.core.port.LlmClient>();
        models.put("m", new Scripted(text(WORK_TEXT), text(VALID)));
        models.put("n", new Scripted(text(WORK_TEXT), text(VALID)));
        PermissionPolicy parent = a -> Decision.ALLOW;
        WorkspacePolicies deriving = (p, child, workspace) -> {
            derived.add(workspace);
            return action -> action.tool().equals("w") ? Decision.DENY : p.check(action);
        };
        var runtime = SubagentRuntime.builder("s1", settings, models::get).store(new ListStore())
                .workspacePolicies(deriving).build();
        try {
            var root = new AgentId("root");
            runtime.registerRoot(root, "root", new CancellationToken(), List.of(simple("r"), simple("w")), parent,
                    in -> { }, () -> 1);
            var worktree = dir.resolve("wt");
            var inWorktree = runtime.spawn(root, SpawnRequest.of("one", "Task one").withModel("m")
                    .withIsolation("worktree").withWorkdir(worktree));
            var shared = runtime.spawn(root, SpawnRequest.of("two", "Task two").withModel("n")
                    .withIsolation("shared"));
            assertEquals(List.of(worktree), derived);
            var use = new ToolUse("u", "w", MAPPER.createObjectNode());
            assertEquals(Decision.DENY, runtime.policyOf(inWorktree).orElseThrow().check(use));
            assertEquals(Decision.ALLOW, runtime.policyOf(shared).orElseThrow().check(use));
            assertTrue(runtime.policyOf(inWorktree).orElseThrow()
                    .check(new ToolUse("u", "nothere", MAPPER.createObjectNode())) == Decision.DENY, "tool set still applies");
            runtime.await(inWorktree, CancellationToken.none());
            runtime.await(shared, CancellationToken.none());
        } finally {
            runtime.close();
        }
    }
}
