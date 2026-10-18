"""/help shows the commands."""
import harness


def run(check):
    env = harness.Env()
    try:
        s = env.start()
        check.ok("started", s.wait_started())
        s.send("/help")
        s.key("Enter")
        check.ok("help lists /quit or /agents", s.wait_for("/quit", 10) or s.wait_for("/agents", 5), s.view.text())
        check.ok("no model request for /help", len(env.server.requests) == 0)
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
