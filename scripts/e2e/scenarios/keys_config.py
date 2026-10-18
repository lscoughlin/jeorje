"""A project config rebinds the hotkeys of the chat screen. A conflict stops the start."""
import harness


def input_text(s):
    """The text of the input line: the second row from the bottom, without the prompt."""
    rows = s.view.text().split("\n")
    return rows[s.rows - 2][1:].strip() if len(rows) >= s.rows - 1 else ""


def run(check):
    # --- rebound keys ---
    env = harness.Env()
    try:
        env.project.config_yaml('ui:\n  keys:\n    agents: "Ctrl-X g"\n    files: F3\n')
        env.project.write("alpha.txt", "alpha\n")
        s = env.start(args=["--trust"])
        check.ok("started with the key config", s.wait_started(), s.stream_text()[-400:])

        s.key("Ctrl-X a")
        s.wait_idle(0.6)
        check.ok("old agents chord does nothing", s.view.find("Agents - ") is None, s.view.text())
        s.key("Ctrl-X f")
        s.wait_idle(0.6)
        check.ok("old files chord does nothing", s.view.find("open/close, ") is None, s.view.text())
        check.ok("old chords leave the input empty", input_text(s).startswith("main [idle]") or input_text(s) == "", input_text(s))

        s.key("Ctrl-X g")
        check.ok("Ctrl-X g opens the switcher", s.wait_for("Agents - ", 10), s.view.text())
        s.key("Ctrl-X a")
        s.wait_idle(0.5)
        check.ok("old chord does not close the switcher", s.view.find("Agents - ") is not None, s.view.text())
        s.key("Ctrl-X g")
        s.wait_idle(0.6)
        check.ok("Ctrl-X g closes the switcher", s.view.find("Agents - ") is None, s.view.text())

        s.key("F3")
        check.ok("F3 opens the browser", s.wait_for("open/close, ", 10), s.view.text())
        check.ok("browser lists alpha.txt", s.view.find("alpha.txt") is not None, s.view.text())
        s.key("F3")
        s.wait_idle(0.6)
        check.ok("F3 closes the browser", s.view.find("open/close, ") is None, s.view.text())
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
    finally:
        env.close()

    # --- browser keys from the config ---
    env = harness.Env()
    try:
        env.project.config_yaml('ui:\n  keys:\n    browserFilter: "Ctrl-G"\n    browserExit: "q"\n')
        env.project.write("alpha.txt", "alpha\n")
        env.project.write("beta.md", "beta\n")
        s = env.start(args=["--trust"])
        check.ok("started with browser keys", s.wait_started(), s.stream_text()[-400:])
        s.key("Ctrl-X f")
        check.ok("browser opens", s.wait_for("open/close, ", 10), s.view.text())
        s.key("Esc")
        s.wait_idle(0.5)
        check.ok("Esc no longer closes the browser", s.view.find("open/close, ") is not None, s.view.text())
        s.send("/")
        s.wait_idle(0.4)
        check.ok("slash no longer starts the filter", s.view.find("[filter:") is None and s.view.find("beta.md"),
                 s.view.text())
        s.key("Ctrl-G")
        s.send("bet")
        s.wait_idle(0.5)
        check.ok("Ctrl-G starts the filter", s.view.find("[filter: bet]") is not None, s.view.text())
        check.ok("filter hides alpha.txt", s.view.find("alpha.txt") is None, s.view.text())
        s.key("Enter")
        s.send("q")
        s.wait_idle(0.6)
        check.ok("q closes the browser", s.view.find("open/close, ") is None, s.view.text())
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
    finally:
        env.close()

    # --- a conflict stops the start ---
    env = harness.Env()
    try:
        env.project.config_yaml('ui:\n  keys:\n    agents: "Ctrl-X f"\n')
        s = env.start(args=["--trust"])
        code = s.exit_code(40)
        text = s.stream_text()
        check.ok("the program exits", code is not None, text[-300:])
        check.ok("exit code is not 0", code not in (None, 0), code)
        check.ok("error names the key config", "key config has an error" in text.replace("\n", " "), text[-600:])
        check.ok("error names both actions", "'agents'" in text and "'files'" in text, text[-600:])
        check.ok("error names the key", "Ctrl-X f" in text, text[-600:])
        check.ok("the interface did not start", not s.entered_alt_screen(), text[-300:])
        check.ok("no model request", len(env.server.requests) == 0)
    finally:
        env.close()
