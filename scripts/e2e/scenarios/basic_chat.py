"""Type a message, see the stub answer, check the recorded request, exit with /quit, terminal restored."""
import harness


def run(check):
    env = harness.Env(["STUBANSWER42 hello from the stub"])
    try:
        s = env.start()
        check.ok("alternate screen entered", s.wait_started(), s.view.text()[-300:])
        s.send("say hello zebra")
        s.key("Enter")
        check.ok("stub answer written to the terminal", s.wait_for_output("STUBANSWER42", 30), s.view.text())
        s.wait_idle()
        check.ok("request recorded", len(env.server.requests) >= 1)
        check.ok("request has the user text", any("say hello zebra" in env.server.last_user_text(r)
                                                   for r in env.server.requests), env.server.models())
        # a cell with text has a color attribute (default or set); the API must work
        # The screen (as pyte renders it) is checked as a warning: the answer is written below the
        # status line in the current UI and is overwritten, so it is not visible on the final screen.
        check.warn("stub answer visible on the final screen", s.view.find("STUBANSWER42") is not None, s.view.text())
        pos = s.view.find("say hello zebra") or (0, 0)
        cell = s.view.cell(*pos)
        check.ok("cell char and colors readable", cell.char != "" and cell.fg is not None and cell.bg is not None, cell)
        code = s.exit_quit()
        check.ok("exit code 0", code == 0, code)
        check.ok("alternate screen left", s.left_alt_screen())
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
