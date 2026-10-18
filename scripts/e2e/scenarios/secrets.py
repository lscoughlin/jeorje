"""Secrets: a tool prints the value of OPENROUTER_API_KEY. The key appears in no file of the project, in no log file,
and in no chunk of the index. The model never receives the key in a tool result."""
import os
import time

import harness
from harness import tool_call

KEY = "sk-or-v1-9f8e7d6c5b4a39281706f5e4d3c2b1a0"
HEX = "9f8e7d6c5b4a39281706f5e4d3c2b1a0"


def wait_requests(server, n, timeout=40):
    end = time.time() + timeout
    while time.time() < end and len(server.requests) < n:
        time.sleep(0.1)
    return len(server.requests) >= n


def tool_texts(body):
    return "\n".join(harness.content_text(m.get("content")) for m in body.get("messages", [])
                     if m.get("role") == "tool")


def files_with_key(root):
    """Paths of files under root whose bytes contain the key or its hex part."""
    found = []
    for base, _dirs, names in os.walk(root):
        for name in names:
            path = os.path.join(base, name)
            try:
                with open(path, "rb") as f:
                    data = f.read()
            except OSError:
                continue
            if KEY.encode() in data or HEX.encode() in data:
                found.append(os.path.relpath(path, root))
    return found


def run(check):
    env = harness.Env([
        [tool_call("read", {"path": "secret.txt"})],
        [tool_call("bash", {"command": "cat secret.txt"})],
        "FIRST-DONE",
        # Second message: the model repeats the whole key in a recall query. The index must not hold it.
        [tool_call("recall", {"query": KEY})],
        "SECOND-DONE",
    ], )
    try:
        env.project.write("secret.txt", "api credential line: " + KEY + "\n")
        s = env.start(env={"OPENROUTER_API_KEY": KEY})
        check.ok("started", s.wait_started(), s.view.text()[-300:])

        s.send("show me the secret file")
        s.key("Enter")
        check.ok("the loop reached the third request", wait_requests(env.server, 3), len(env.server.requests))
        s.wait_idle(1.0)
        results = tool_texts(env.server.requests[-1]) if env.server.requests else ""
        check.ok("tool results reached the model", "api credential line" in results, results[-300:])
        check.ok("the model got no key in a tool result", KEY not in results and HEX not in results, results[-300:])
        check.ok("the key is masked in the tool result", "***" in results, results[-300:])

        s.send("search the history for that value")
        s.key("Enter")
        check.ok("the recall loop reached the fifth request", wait_requests(env.server, 5), len(env.server.requests))
        s.wait_idle(1.0)
        recall_result = tool_texts(env.server.requests[-1]) if env.server.requests else ""
        check.ok("the recall result has no key", KEY not in recall_result and HEX not in recall_result,
                 recall_result[-400:])
        check.ok("the screen never shows the key", KEY not in s.stream_text(), "")

        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
        s.close()

        # The files: the key is in no file under the project directory and the home directory (logs, sessions,
        # transcript, audit log, index). The secret file itself is the only allowed place.
        found = [f for f in files_with_key(env.project.root) if f != os.path.join("work", "secret.txt")]
        check.ok("no file holds the key", not found, found)
        sessions = os.path.join(env.project.work, ".jeorje", "sessions")
        check.ok("a session directory exists", os.path.isdir(sessions) and os.listdir(sessions), sessions)
        transcript = [os.path.join(b, n) for b, _d, ns in os.walk(sessions) for n in ns if n == "transcript.jsonl"]
        check.ok("the transcript exists and is not empty", bool(transcript) and os.path.getsize(transcript[0]) > 0,
                 transcript)
        if transcript:
            text = open(transcript[0]).read()
            check.ok("the transcript has the masked text", "***" in text and "api credential line" in text, text[-300:])
        audit = [os.path.join(b, n) for b, _d, ns in os.walk(sessions) for n in ns if n == "audit.jsonl"]
        check.ok("the audit log exists", bool(audit))
    finally:
        env.close()
