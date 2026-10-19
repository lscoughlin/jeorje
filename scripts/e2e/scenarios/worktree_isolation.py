"""Worktree isolation: a subagent in a worktree cannot read the main working tree by absolute path. It writes a file
in its own worktree. The parent gets the file by the merge back, and the worktree is removed."""
import os
import subprocess
import time

import harness
from harness import tool_call

MARKER = "MAIN-TREE-MARKER-31337"
SUMMARY = """Outcome: completed
Done: The read of the main tree file was refused. I wrote sub.txt in my worktree.
Files: sub.txt
Branch: none
Decisions: none
Verification: none
Open issues: none
Keywords: sub.txt, main.txt, worktree, refused"""


def system_text(body):
    return "\n".join(harness.content_text(m.get("content")) for m in body.get("messages", [])
                     if m.get("role") == "system")


def tool_messages(body):
    return [harness.content_text(m.get("content")) for m in body.get("messages", []) if m.get("role") == "tool"]


def wait_for(cond, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.2)
    return cond()


def git(work, *args):
    return subprocess.run(["git", *args], cwd=work, capture_output=True, text=True).stdout


def run(check):
    holder = {}

    def chooser(body):
        sys = system_text(body)
        tools = tool_messages(body)
        if 'You are the subagent "wt-writer"' in sys:
            holder["sub_requests"] = holder.get("sub_requests", 0) + 1
            if len(tools) == 0:
                return [tool_call("read", {"path": holder["main_file"]})]
            if len(tools) == 1:
                return [tool_call("write", {"path": "sub.txt", "content": "written in the worktree\n"})]
            return SUMMARY
        if len(tools) == 0:
            return [tool_call("spawn_agent", {
                "name": "wt-writer", "isolation": "worktree", "mode": "wait",
                "task": "Read the file " + holder["main_file"] + " and then write the file sub.txt in your own "
                        "working directory."})]
        return "MAIN-DONE after the merge"

    env = harness.Env()
    env.server.chooser = chooser
    try:
        env.project.write("main.txt", MARKER + "\n")
        holder["main_file"] = os.path.realpath(env.project.path("main.txt"))
        env.project.git_init()
        s = env.start()
        check.ok("started", s.wait_started(), s.view.text()[-300:])
        s.send("delegate the work to a subagent")
        s.key("Enter")
        done = wait_for(lambda: any("MAIN-DONE" in harness.content_text(m.get("content"))
                                    for r in list(env.server.requests) for m in r.get("messages", [])
                                    if m.get("role") == "tool") or holder.get("sub_requests", 0) >= 3, 90)
        check.ok("the subagent ran", done, holder)
        sub_tool_msgs = []
        for r in list(env.server.requests):
            if 'You are the subagent "wt-writer"' in system_text(r):
                sub_tool_msgs = tool_messages(r)
        refused = bool(sub_tool_msgs) and "Permission denied: the call to read did not run." in sub_tool_msgs[0]
        check.ok("the read of the main tree was refused", refused, sub_tool_msgs)
        check.ok("the subagent never saw the main tree text", not any(MARKER in t for t in sub_tool_msgs), sub_tool_msgs)

        merged = wait_for(lambda: os.path.exists(env.project.path("sub.txt")), 90)
        check.ok("the parent has the file after the merge back", merged, git(env.project.work, "status", "--short"))
        if merged:
            check.ok("the file has the text of the subagent", env.project.read("sub.txt") == "written in the worktree\n")
        gone = wait_for(lambda: len([l for l in git(env.project.work, "worktree", "list").splitlines() if l.strip()]) == 1, 60)
        check.ok("the worktree is removed", gone, git(env.project.work, "worktree", "list"))
        wt_root = env.project.path(".jeorje/worktrees")
        left = [os.path.join(b, d) for b, ds, _ in os.walk(wt_root) for d in ds] if os.path.isdir(wt_root) else []
        check.ok("no worktree directory is left", not any(os.path.exists(os.path.join(p, "sub.txt")) or
                                                          os.path.exists(os.path.join(p, ".git")) for p in left), left)
        check.ok("the main file is unchanged", env.project.read("main.txt") == MARKER + "\n")
        s.exit_quit()
    finally:
        env.close()
