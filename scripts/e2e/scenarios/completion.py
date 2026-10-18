"""Tab completion on the input line: commands, skills, custom commands, and file paths."""
import harness


def input_row(s):
    return s.view.text().split("\n")[s.rows - 2]


def clear_input(s):
    s.key("Ctrl-U")
    s.wait_idle(0.3)


def run(check):
    env = harness.Env()
    try:
        p = env.project
        p.skill("deploy-check", "DEPLOY-BODY do the checks.", "Checks a deploy.")
        p.write(".agents/commands/shipit.md", "---\ndescription: Ship it\n---\nSHIP-BODY $ARGUMENTS\n")
        p.write("alpha.txt", "alpha\n")
        p.write("src/Main.java", "class Main {}\n")
        p.write("src/Mini.java", "class Mini {}\n")
        s = env.start(args=["--trust"])
        check.ok("started", s.wait_started(), s.view.text()[-300:])

        # a unique command name completes with a trailing space
        s.send("/he")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("/he and Tab gives /help", "> /help" in input_row(s), input_row(s))
        s.key("Enter")
        check.ok("the completed command runs", s.wait_for("/quit", 10) or s.wait_for("/agents", 5), s.view.text())
        check.ok("no model request for /help", len(env.server.requests) == 0)

        # a skill and a custom command
        s.send("/dep")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("skill name completes", "> /deploy-check" in input_row(s), input_row(s))
        clear_input(s)
        s.send("/shi")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("custom command completes", "> /shipit" in input_row(s), input_row(s))
        clear_input(s)

        # many matches: the common prefix is inserted and the candidates are listed
        s.send("/co")
        s.key("Tab")
        s.wait_idle(0.6)
        check.ok("candidates are listed", s.view.find("matches:") is not None, s.view.text())
        check.ok("candidates include /compact", s.view.find("/compact") is not None, s.view.text())
        check.ok("candidates include /commands", s.view.find("/commands") is not None, s.view.text())
        check.ok("common prefix is inserted", "> /com" in input_row(s), input_row(s))
        clear_input(s)

        # file paths
        s.send("/edit alp")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("/edit completes a file", "> /edit alpha.txt" in input_row(s), input_row(s))
        clear_input(s)
        s.send("/files add sr")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("/files add completes a directory", "> /files add src/" in input_row(s), input_row(s))
        s.send("M")
        s.key("Tab")
        s.wait_idle(0.6)
        check.ok("two files match", s.view.find("Main.java") is not None and s.view.find("Mini.java") is not None,
                 s.view.text())
        check.ok("common prefix of the paths", "> /files add src/M" in input_row(s), input_row(s))
        s.send("a")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("one file left completes", "> /files add src/Main.java" in input_row(s), input_row(s))
        clear_input(s)
        s.send("/implement pl")
        s.key("Tab")
        s.wait_idle(0.5)
        check.ok("no match leaves the line", "> /implement pl" in input_row(s), input_row(s))
        clear_input(s)

        # text that is not a command stays as typed
        s.send("hello")
        s.key("Tab")
        s.wait_idle(0.4)
        check.ok("plain text is not changed", "> hello" in input_row(s), input_row(s))
        s.key("Ctrl-U")
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
