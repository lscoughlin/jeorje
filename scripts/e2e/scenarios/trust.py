"""Trust: the first start asks, a stored answer is read at the next start, and an untrusted project does not
list its skills."""
import time

import harness


def wait_requests(server, n, timeout=30):
    end = time.time() + timeout
    while time.time() < end and len(server.requests) < n:
        time.sleep(0.1)
    return len(server.requests) >= n


def system_text(body):
    return "\n".join(harness.content_text(m.get("content")) for m in body.get("messages", [])
                     if m.get("role") == "system")


def quit_session(s):
    s.key("Ctrl-D")
    if s.exit_code(10) is None:
        s.key("Ctrl-D")
        s.exit_code(10)


def first_request_system(env, s, text="hello"):
    s.wait_idle()
    s.send(text)
    s.key("Enter")
    wait_requests(env.server, 1)
    return system_text(env.server.requests[0]) if env.server.requests else ""


def make_project(env):
    env.project.agents_md("Project rules.\n")
    env.project.skill("demo", "DEMO-BODY-55 steps.", "Runs the demo task.")


def run(check):
    # 1. The first start shows the prompt. The answer is yes.
    env = harness.Env(["ONE", "TWO"])
    try:
        make_project(env)
        s = env.start()
        s.wait_started()
        check.ok("trust prompt on the first start", s.wait_for("trust", 20), s.view.text())
        s.send("y")
        s.key("Enter")
        s.pump(1.0)
        if s.view.find("now trusted") is None:
            s.key("Enter")
            s.pump(0.5)
        check.ok("trust accepted", s.wait_for("now trusted", 10), s.view.text())
        sys1 = first_request_system(env, s)
        check.ok("skill listed after yes", "<name>demo</name>" in sys1, sys1[-400:])
        check.ok("trust file written", "projects" in open(env.project.root + "/.jeorje/trust.yaml").read())
        quit_session(s)

        # 2. A second start in the same project: no prompt, the skill loads.
        env.server.requests.clear()
        s2 = env.start()
        s2.wait_started()
        s2.pump(1.5)
        check.ok("no trust prompt at the second start", s2.view.find("Jeorje loads them only if you trust") is None,
                 s2.view.text())
        sys2 = first_request_system(env, s2)
        check.ok("skill listed at the second start", "<name>demo</name>" in sys2, sys2[-400:])
        quit_session(s2)
    finally:
        env.close()

    # 3. An untrusted project does not list its skills.
    env = harness.Env(["ONE"])
    try:
        make_project(env)
        s = env.start()
        s.wait_started()
        check.ok("prompt shown", s.wait_for("trust", 20), s.view.text())
        s.send("n")
        s.key("Enter")
        check.ok("stays untrusted", s.wait_for("stays untrusted", 10), s.view.text())
        sys3 = first_request_system(env, s)
        check.ok("skill not listed", "demo" not in sys3 and "<available_skills>" not in sys3, sys3[-400:])
        check.ok("AGENTS.md still loaded", "Project rules." in sys3, sys3[-400:])
        quit_session(s)
    finally:
        env.close()
