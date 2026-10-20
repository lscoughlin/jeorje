"""MCP: a configured stdio server offers a tool. The model gets the tool, a call returns its result, /mcp lists
the server, and the server process ends when the session ends."""
import json
import os
import sys
import time

import harness
from harness import tool_call

SERVER = r'''
import json, os, sys
with open(sys.argv[1], "w") as f:
    f.write(str(os.getpid()))
for line in sys.stdin:
    request = json.loads(line)
    if "id" not in request:
        continue
    method = request.get("method")
    if method == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "pyfake"}}
    elif method == "tools/list":
        result = {"tools": [{"name": "greet", "description": "Greets a person.",
                             "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}},
                                             "required": ["name"]}}]}
    else:
        name = request["params"]["arguments"].get("name", "")
        result = {"content": [{"type": "text", "text": "MCP-HELLO " + name}]}
    print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)
'''


def wait_requests(server, n, timeout=40):
    end = time.time() + timeout
    while time.time() < end and len(server.requests) < n:
        time.sleep(0.1)
    return len(server.requests) >= n


def tool_texts(body):
    return "\n".join(harness.content_text(m.get("content")) for m in body.get("messages", [])
                     if m.get("role") == "tool")


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def run(check):
    env = harness.Env([[tool_call("mcp__pyfake__greet", {"name": "Ada"})], "MCP-DONE"])
    try:
        script = env.project.write("pyfake.py", SERVER)
        pidfile = env.project.path("pyfake.pid")
        config = env.project.path("mcp-config.json")
        with open(config, "w") as f:
            json.dump({"mcp": {"servers": {"pyfake": {"command": sys.executable, "args": [script, pidfile],
                                                     "timeoutSeconds": 20}}}}, f)
        s = env.start(args=["--config", config])
        check.ok("started", s.wait_started(), s.view.text()[-300:])

        s.send("greet Ada")
        s.key("Enter")
        check.ok("the loop reached the second request", wait_requests(env.server, 2), len(env.server.requests))
        s.wait_idle(1.0)
        names = [t["function"]["name"] for t in env.server.requests[0].get("tools", [])]
        check.ok("the model got the MCP tool", "mcp__pyfake__greet" in names, names)
        results = tool_texts(env.server.requests[-1])
        check.ok("the tool result reached the model", "MCP-HELLO Ada" in results, results[-300:])

        s.send("/mcp")
        s.key("Enter")
        check.ok("/mcp lists the server", s.wait_for("pyfake: connected", timeout=10), s.view.text()[-400:])
        check.ok("/mcp lists the tool", "mcp__pyfake__greet" in s.view.text(), s.view.text()[-400:])

        check.ok("the server runs", os.path.exists(pidfile))
        pid = int(open(pidfile).read()) if os.path.exists(pidfile) else 0
        s.exit_quit()
        s.close()
        end = time.time() + 10
        while pid and alive(pid) and time.time() < end:
            time.sleep(0.1)
        check.ok("the server ended with the session", pid != 0 and not alive(pid), pid)
    finally:
        env.close()
