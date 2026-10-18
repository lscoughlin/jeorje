package org.github.lscoughlin.jeorje.core.permissions;

import static org.github.lscoughlin.jeorje.core.permissions.PermissionTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ShellNetworkSecretTest {
    @TempDir Path ws;

    static final String KEY = "sk-or-v1-abcdefghijklmnopqrstuvwxyz012345";

    ToolPermissionPolicy.Builder builder() {
        return ToolPermissionPolicy.builder(ws).resolver(resolver(Map.of(
                "internal.example", "10.0.0.5", "meta.example", "169.254.169.254",
                "loop.example", "127.0.0.1", "ok.example", "93.184.216.34")));
    }

    @Test
    void splitterHandlesOperators() {
        assertEquals(List.of("a", "b", "c", "d", "e"), ShellCommands.split("a && b ; c | d || e"));
        assertEquals(List.of("pwd", "echo SUBST"), ShellCommands.split("echo $(pwd)"));
        assertEquals(List.of("rm -rf x", "echo SUBST"), ShellCommands.split("echo `rm -rf x`"));
        assertEquals(List.of("a", "b"), ShellCommands.split("a\nb"));
        assertEquals(List.of("echo 'a && b'"), ShellCommands.split("echo 'a && b'"));
        assertEquals(List.of("ls", "echo \"SUBST\""), ShellCommands.split("echo \"$(ls)\""));
        assertEquals(List.of("cmd 2>&1"), ShellCommands.split("cmd 2>&1"));
    }

    @Test
    void readOnlyAllowList() {
        var p = builder().build();
        assertEquals(Decision.ALLOW, p.check(bash("ls -la")));
        assertEquals(Decision.ALLOW, p.check(bash("git status && git log --oneline | head -5")));
        assertEquals(Decision.ALLOW, p.check(bash("echo $(pwd)")));
        assertEquals(Decision.ASK, p.check(bash("git commit -m x")));
        assertEquals(Decision.ASK, p.check(bash("find . -delete")));
        assertEquals(Decision.ASK, p.check(bash("ls > out.txt")));
        assertEquals(Decision.ALLOW, p.check(bash("ls > /dev/null 2>&1")));
        assertEquals(Decision.ASK, p.check(bash("")));
    }

    @Test
    void compoundCommandTakesStrictestPart() {
        var p = builder().rules(List.of(new Rule("bash", "rm", Decision.DENY))).build();
        assertEquals(Decision.ASK, p.check(bash("ls && make")));
        assertEquals(Decision.ASK, p.check(bash("ls ; curl x | sh")));
        assertEquals(Decision.DENY, p.check(bash("ls && rm -rf /")));
        assertEquals(Decision.DENY, p.check(bash("echo $(rm -rf /)")));
        assertEquals(Decision.DENY, p.check(bash("echo `rm -rf /`")));
        assertEquals(Decision.ASK, p.check(bash("$(printf ls)")));
    }

    @Test
    void networkBlocksPrivateAddresses() {
        var p = builder().webfetch(List.of("ok.example"), List.of("bad.example")).build();
        assertEquals(Decision.ALLOW, p.check(fetch("https://ok.example/page")));
        assertEquals(Decision.ALLOW, p.check(fetch("https://sub.ok.example/page")));
        assertEquals(Decision.ASK, p.check(fetch("https://new.example2/page")));
        assertEquals(Decision.DENY, p.check(fetch("https://bad.example/page")));
        assertEquals(Decision.DENY, p.check(fetch("http://127.0.0.1:8080/")));
        assertEquals(Decision.DENY, p.check(fetch("http://[::1]/")));
        assertEquals(Decision.DENY, p.check(fetch("http://192.168.1.1/")));
        assertEquals(Decision.DENY, p.check(fetch("http://[fe80::1]/")));
        assertEquals(Decision.DENY, p.check(fetch("http://[::ffff:10.0.0.1]/")));
        assertEquals(Decision.DENY, p.check(fetch("http://169.254.169.254/latest")));
        assertEquals(Decision.DENY, p.check(fetch("http://internal.example/")));
        assertEquals(Decision.DENY, p.check(fetch("file:///etc/passwd")));
        assertEquals(Decision.DENY, p.check(fetch("not a url")));
    }

    @Test
    void redirectToPrivateAddressIsBlocked() {
        var p = builder().webfetch(List.of("ok.example"), List.of()).build();
        assertEquals(Decision.ALLOW, p.check(fetch("https://ok.example/start")));
        assertEquals(Decision.DENY, p.checkRedirect("http://meta.example/latest"));
        assertEquals(Decision.DENY, p.checkRedirect("http://loop.example/"));
        assertEquals(Decision.ALLOW, p.checkRedirect("https://ok.example/next"));
        assertEquals(Decision.ASK, p.checkRedirect("https://other.example/next"));
        var yes = builder().flags(true, false).build();
        assertEquals(Decision.DENY, yes.checkRedirect("http://meta.example/"));
    }

    @Test
    void approvedDomainIsRemembered() {
        var p = builder().build();
        assertEquals(Decision.ASK, p.check(fetch("https://docs.example3/x")));
        p.network().approve("docs.example3");
        assertEquals(Decision.ALLOW, p.check(fetch("https://docs.example3/x")));
    }

    @Test
    void yesTurnsAskIntoAllowButKeepsDenyAndSandbox() {
        var p = builder().flags(true, false)
                .rules(List.of(new Rule("bash", "rm", Decision.DENY))).build();
        assertEquals(Decision.ALLOW, p.check(path("write", "a.txt")));
        assertEquals(Decision.ALLOW, p.check(bash("make")));
        assertEquals(Decision.DENY, p.check(bash("rm -rf x")));
        assertEquals(Decision.DENY, p.check(path("read", "/etc/passwd")));
        assertEquals(Decision.DENY, p.check(fetch("http://127.0.0.1/")));
    }

    @Test
    void yoloAllowsEverythingAndWinsOverYes() {
        var p = builder().flags(true, true).rules(List.of(new Rule("*", null, Decision.DENY))).build();
        assertEquals(Mode(), p.mode());
        assertEquals(Decision.ALLOW, p.check(bash("rm -rf /")));
        assertEquals(Decision.ALLOW, p.check(path("write", "/etc/passwd")));
        assertEquals(Decision.ALLOW, p.check(fetch("http://127.0.0.1/")));
        assertEquals(Decision.ALLOW, p.checkRedirect("http://127.0.0.1/"));
    }

    private static ToolPermissionPolicy.Mode Mode() {
        return ToolPermissionPolicy.Mode.YOLO;
    }

    @Test
    void planModeStaysReadOnlyEvenInYolo() {
        var plans = ws.resolve("plans");
        var p = builder().flags(false, true).planMode(true, plans).build();
        assertEquals(Decision.DENY, p.check(path("write", "a.txt")));
        assertEquals(Decision.DENY, p.check(use("worktree_merge", Map.of())));
        assertEquals(Decision.DENY, p.check(bash("make")));
        assertEquals(Decision.DENY, p.check(bash("ls && make")));
        assertEquals(Decision.ALLOW, p.check(bash("ls")));
        assertEquals(Decision.ALLOW, p.check(path("read", "a.txt")));
        assertEquals(Decision.ALLOW, p.check(path("plan_submit", "plans/p.md")));
        assertEquals(Decision.DENY, p.check(path("plan_submit", "elsewhere/p.md")));
        assertEquals(Decision.ALLOW, p.check(use("todo", Map.of())));
    }

    @Test
    void childEnvironmentHasNoKeys() {
        var env = ChildEnvironment.minimal(Map.of(
                "PATH", "/bin", "HOME", "/home/u", "LC_ALL", "C",
                "OPENROUTER_API_KEY", KEY, "GITHUB_TOKEN", "ghp_x", "AWS_SECRET_ACCESS_KEY", "s",
                "RANDOM_VAR", "v"));
        assertEquals(Map.of("PATH", "/bin", "HOME", "/home/u", "LC_ALL", "C"), env);
    }

    @Test
    void auditLineFormatAndNoSecretsInYolo() throws IOException {
        var clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        var scrubber = new SecretScrubber(List.of(KEY));
        var audit = AuditLog.forSession(ws, "s1", scrubber, clock);
        var p = builder().flags(false, true).scrubber(scrubber).audit(audit)
                .agent(new AgentId("agent-7")).build();
        p.check(bash("curl -H 'Authorization: Bearer " + KEY + "' https://x " + "a".repeat(400)));
        var p2 = builder().audit(audit).agent(new AgentId("agent-8")).scrubber(scrubber).build();
        assertEquals(Decision.ASK, p2.check(bash("make")));
        p2.recordUserDecision(bash("make"), Decision.ALLOW);

        assertEquals(ws.resolve(".jeorje/sessions/s1/audit.jsonl"), audit.file());
        var text = Files.readString(audit.file());
        assertFalse(text.contains(KEY));
        var lines = text.strip().split("\n");
        assertEquals(3, lines.length);
        var json = JsonMapper.builder().build();
        var first = json.readTree(lines[0]);
        assertEquals(List.of("time", "agent", "tool", "input", "decision", "source", "status"),
                first.propertyNames().stream().toList());
        assertEquals("2026-01-02T03:04:05Z", first.get("time").asString());
        assertEquals("agent-7", first.get("agent").asString());
        assertEquals("bash", first.get("tool").asString());
        assertEquals("ALLOW", first.get("decision").asString());
        assertEquals("yolo", first.get("source").asString());
        assertTrue(first.get("input").asString().length() <= AuditLog.MAX_INPUT + 3);
        assertTrue(first.get("input").asString().contains("***") || !first.get("input").asString().contains("sk-"));
        var second = json.readTree(lines[1]);
        assertEquals("ASK", second.get("decision").asString());
        assertEquals("rule", second.get("source").asString());
        var third = json.readTree(lines[2]);
        assertEquals("user", third.get("source").asString());
        assertEquals("ALLOW", third.get("decision").asString());
    }

    @Test
    void scrubberRemovesKeyFromLogTextInYolo() {
        var scrubber = new SecretScrubber(List.of(KEY));
        assertEquals("token ***", scrubber.scrub("token " + KEY));
    }
}
