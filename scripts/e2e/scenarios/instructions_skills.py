"""AGENTS.md text and the skill catalog in the system prompt, nested AGENTS.md in read results, the skill tool,
the /<skill> invocation, and a custom command."""
import json
import time

import harness
from harness import tool_call


def wait_requests(server, n, timeout=30):
    end = time.time() + timeout
    while time.time() < end and len(server.requests) < n:
        time.sleep(0.1)
    return len(server.requests) >= n


def system_text(body):
    return "\n".join(harness.content_text(m.get("content")) for m in body.get("messages", [])
                     if m.get("role") == "system")


def tool_texts(body):
    return "\n".join(harness.content_text(m.get("content")) for m in body.get("messages", [])
                     if m.get("role") == "tool")


def run(check):
    env = harness.Env([
        [tool_call("read", {"path": "sub/file.txt"})], "READ-DONE",
        [tool_call("skill", {"name": "demo", "arguments": "from-the-tool"})], "SKILL-DONE",
        "SLASH-DONE", "COMMAND-DONE",
    ])
    try:
        p = env.project
        p.agents_md("# Rules\nROOT-RULE-42 always be brief.\n")
        p.write("sub/AGENTS.md", "NESTED-RULE-77 use tabs in this folder.\n")
        p.write("sub/file.txt", "hello file\n")
        p.write("sub/deeper/AGENTS.md", "DEEPER-RULE-88 closest wins.\n")
        p.write("sub/deeper/other.txt", "deep file\n")
        p.skill("demo", "DEMO-BODY-55 do the demo steps.", "Runs the demo task. Use for demos.")
        p.write(".agents/commands/greet.md", "---\ndescription: Greet\n---\nGREET-BODY-66 hello $ARGUMENTS\n")
        s = env.start(args=["--trust"])
        check.ok("started", s.wait_started(), s.view.text()[-300:])

        s.send("first message")
        s.key("Enter")
        check.ok("first request recorded", wait_requests(env.server, 1))
        first = env.server.requests[0]
        sys0 = system_text(first)
        check.ok("system prompt has the AGENTS.md text", "ROOT-RULE-42" in sys0, sys0[-600:])
        check.ok("system prompt has the catalog entry", "<name>demo</name>" in sys0 and "Runs the demo task" in sys0,
                 sys0[-600:])
        check.ok("catalog entry has the location", ".agents/skills/demo/SKILL.md</location>" in sys0, sys0[-600:])
        check.ok("nested text not in the system prompt", "NESTED-RULE-77" not in sys0)
        names = [t["function"]["name"] for t in first.get("tools", [])]
        check.ok("skill tool is registered", "skill" in names, names)

        s.wait_idle()
        s.send("read the nested file")
        s.key("Enter")
        check.ok("request after the read call", wait_requests(env.server, 3))
        s.wait_idle()
        after_read = env.server.requests[2]
        results = tool_texts(after_read)
        check.ok("read result has the nested AGENTS.md", "NESTED-RULE-77" in results, results[:500])
        check.ok("read result has the file", "hello file" in results, results[:500])

        s.send("use the skill")
        s.key("Enter")
        check.ok("request after the skill call", wait_requests(env.server, 5))
        s.wait_idle()
        skill_results = tool_texts(env.server.requests[4])
        check.ok("skill result is wrapped", '<skill_content name="demo">' in skill_results
                 and "DEMO-BODY-55" in skill_results and "ARGUMENTS: from-the-tool" in skill_results,
                 skill_results[-500:])

        s.send("/demo arg-one")
        s.key("Enter")
        check.ok("request for /demo", wait_requests(env.server, 6))
        s.wait_idle()
        user = env.server.last_user_text(env.server.requests[5])
        check.ok("/demo sends the body", "DEMO-BODY-55" in user, user[-400:])
        check.ok("/demo sends the argument", "arg-one" in user, user[-400:])

        s.send("/greet world")
        s.key("Enter")
        check.ok("request for /greet", wait_requests(env.server, 7))
        s.wait_idle()
        user = env.server.last_user_text(env.server.requests[6])
        check.ok("custom command expands the argument", "GREET-BODY-66 hello world" in user, user[-300:])
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
