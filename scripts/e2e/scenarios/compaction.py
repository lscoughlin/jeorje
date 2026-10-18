"""Compaction: large tool results over many turns with a small context window.

Checks: the request size stops growing past the budget, a file path of the recent turns stays in the request
after pruning, the todo list is in every request, a pruned chunk has a recall hint, and a scripted recall call
returns the original text.
"""
import json
import re
import time

import harness
from harness import tool_call

TURNS = 10
WINDOW = 40000
MAX_OUTPUT = 1000
TODO_TEXT = "TODO-MARKER-ZETA write the quarterly report"
HINT = re.compile(r"\[(main:\d+\.\d+) tool_result read \| pruned L3 \(0% kept\)\]\n(?:[^\n]*\n)?\[recall: ([^\]]*)\]")


def big_file(i):
    lines = ["path: src/pkg/File%d.txt" % i]
    for k in range(1, 151):
        if k == 60:
            lines.append("line 60 of file %d holds ORIGINALMARK%d for the recall check" % (i, i))
        else:
            lines.append("line %d of file %d: value%d_%d computed by module%d with option%d and a longer tail of words" % (k, i, k * 7 + i, i, k % 13, k % 5))
    return "\n".join(lines) + "\n"


def message_text(m):
    return harness.content_text(m.get("content"))


def request_text(body):
    return json.dumps(body)


def run(check):
    state = {"recall_id": None}

    def chooser(body):
        last = body["messages"][-1]
        if last["role"] == "user":
            text = message_text(last)
            m = re.search(r"work on step (\d+)", text)
            if m:
                i = int(m.group(1))
                if i == 1:
                    return [tool_call("todo", {"items": [{"content": TODO_TEXT, "status": "in_progress"}]}, "c1-todo")]
                return [tool_call("read", {"path": "src/pkg/File%d.txt" % i}, "c%d-read" % i)]
            if "recall now" in text:
                found = HINT.findall(request_text(body).replace("\\n", "\n"))
                state["recall_hits"] = found
                for cid, _ in found:
                    n = int(cid.split(":")[1].split(".")[0])
                    if "ORIGINALMARK%d" % n not in message_text(body["messages"][0]) \
                            and "ORIGINALMARK%d" % n not in request_text(body):
                        state["recall_id"] = cid
                        state["recall_turn"] = n
                        return [tool_call("recall", {"chunkId": cid}, "crecall")]
                return "no pruned chunk found"
            return "ok"
        if last["role"] == "tool":
            cid = last.get("tool_call_id", "")
            if cid == "c1-todo":
                return [tool_call("read", {"path": "src/pkg/File1.txt"}, "c1-read")]
            if cid.endswith("-read"):
                return "done " + cid.split("-")[0][1:]
            if cid == "crecall":
                return "recalled"
        return "ok"

    env = harness.Env()
    env.server.chooser = chooser
    try:
        p = env.project
        for i in range(1, TURNS + 1):
            p.write("src/pkg/File%d.txt" % i, big_file(i))
        p.config_yaml(
            "llm:\n  maxOutputTokens: %d\ncontext:\n  window: %d\n  budgetPercent: 60\n  recentTurns: 2\n"
            "  compaction:\n    protectedRecentTurns: 6\n    aggressiveDropAfterTurns: 1\n" % (MAX_OUTPUT, WINDOW))
        s = env.start(args=["--trust"])
        check.ok("started", s.wait_started(), s.view.text()[-300:])
        server = env.server

        def wait_requests(n, timeout=60):
            end = time.time() + timeout
            while time.time() < end and len(server.requests) < n:
                s.pump(0.2)
            s.wait_idle(quiet=0.8)
            return len(server.requests) >= n

        expected = 0
        starts = []
        for i in range(1, TURNS + 1):
            starts.append(len(server.requests))
            s.send("work on step %d" % i)
            s.key("Enter")
            expected += 3 if i == 1 else 2
            if not check.ok("turn %d finished" % i, wait_requests(expected), len(server.requests)):
                return
        sizes = [len(request_text(server.requests[k])) for k in starts[1:]]
        budget_chars = int((WINDOW * 60 // 100) * 4)
        print("    request sizes at turn starts:", sizes, "budget chars:", budget_chars)
        total_results = sum(len(big_file(i)) for i in range(1, TURNS + 1))
        check.ok("every request is within the budget", all(x <= budget_chars * 1.25 for x in
                                                         (len(request_text(r)) for r in server.requests)),
                 max(len(request_text(r)) for r in server.requests))
        check.ok("request size stops growing", max(sizes[-4:]) <= max(sizes[:5]) * 1.1 and max(sizes) < budget_chars,
                 sizes)
        check.ok("final request is far smaller than all results together", sizes[-1] < total_results * 0.6,
                 (sizes[-1], total_results))

        last_start = server.requests[starts[-1]]
        last_text = request_text(last_start)
        check.ok("pruned chunks have a recall hint line", "pruned L" in last_text and "[recall: " in last_text,
                 last_text[-800:])
        check.ok("a hint-only chunk keeps the path of a recent file",
                 any("files src/pkg/File" in request_text(r) for r in server.requests[3:]),
                 "no topics line with files")
        check.ok("a file path of the recent turns is in the last request", "src/pkg/File%d.txt" % (TURNS - 2) in last_text)

        todo_ok = all(TODO_TEXT in request_text(server.requests[k]) for k in range(2, len(server.requests)))
        check.ok("the todo list is in every request after the todo call", todo_ok,
                 [k for k in range(2, len(server.requests)) if TODO_TEXT not in request_text(server.requests[k])])
        late = server.requests[starts[-1]]
        first_user = next(m for m in late["messages"] if m["role"] == "user")
        check.ok("the todo list is pinned at the top", "[Pinned items]" in message_text(first_user)
                 and TODO_TEXT in message_text(first_user), message_text(first_user)[:400])

        s.send("recall now")
        s.key("Enter")
        check.ok("recall turn finished", wait_requests(expected + 2), len(server.requests))
        check.ok("a pruned chunk was chosen for recall", state["recall_id"] is not None, state.get("recall_hits"))
        if state["recall_id"]:
            after = server.requests[-1]
            tool_msgs = [message_text(m) for m in after["messages"] if m["role"] == "tool"]
            marker = "ORIGINALMARK%d" % state["recall_turn"]
            check.ok("recall returns the original text", any(marker in t for t in tool_msgs),
                     (state["recall_id"], [t[:120] for t in tool_msgs[-2:]]))
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
