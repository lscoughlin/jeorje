"""The agent switcher and the file browser panes: open, close, toggle, filter, and open a file."""
import harness


def run(check):
    env = harness.Env()
    try:
        env.project.write("alpha.txt", "alpha file\n")
        env.project.write("beta.md", "# beta\n")
        env.project.write("src/Gamma.java", "class Gamma {}\n")
        s = env.start()
        check.ok("started", s.wait_started(), s.view.text()[-300:])

        # agent switcher: lists the main agent, Esc closes
        s.key("Ctrl-X a")
        check.ok("switcher shows its title", s.wait_for("Agents - ", 10), s.view.text())
        check.ok("switcher lists the main agent", s.view.find("main |") is not None, s.view.text())
        s.key("Esc")
        s.wait_idle(0.6)
        check.ok("Esc closes the switcher", s.view.find("Agents - ") is None, s.view.text())

        # Ctrl-X a toggles
        s.key("Ctrl-X a")
        check.ok("switcher opens again", s.wait_for("Agents - ", 10), s.view.text())
        s.key("Ctrl-X a")
        s.wait_idle(0.6)
        check.ok("Ctrl-X a closes the switcher", s.view.find("Agents - ") is None, s.view.text())

        # file browser: lists files, filters, opens a file
        s.key("Ctrl-X f")
        check.ok("browser shows its title", s.wait_for("open/close, ", 10), s.view.text())
        check.ok("browser lists alpha.txt", s.view.find("alpha.txt") is not None, s.view.text())
        check.ok("browser lists beta.md", s.view.find("beta.md") is not None, s.view.text())
        check.ok("browser lists the src directory", s.view.find("src/") is not None, s.view.text())
        s.send("/")
        s.pump(0.3)
        s.send("alp")
        s.wait_idle(0.5)
        check.ok("filter keeps alpha.txt", s.view.find("alpha.txt") is not None, s.view.text())
        check.ok("filter hides beta.md", s.view.find("beta.md") is None, s.view.text())
        check.ok("title shows the filter", s.view.find("[filter: alp]") is not None, s.view.text())
        s.key("Enter")
        s.wait_idle(0.4)
        check.ok("Enter ends the filter and keeps the pane", s.view.find("open/close, ") is not None, s.view.text())
        s.key("Down")
        s.key("Enter")
        check.ok("Enter on a file opens the editor", s.wait_for("alpha.txt", 10) and s.wait_for("Ln 1", 10),
                 s.view.text())
        check.ok("editor shows the file text", s.view.find("alpha file") is not None, s.view.text())
        s.key("Alt-x")
        s.wait_idle(0.8)
        check.ok("Alt-X returns to the chat", s.view.find("open/close, ") is None and s.view.find("Ln 1") is None,
                 s.view.text())

        # Esc closes the browser
        s.key("Ctrl-X f")
        check.ok("browser opens again", s.wait_for("open/close, ", 10), s.view.text())
        s.key("Esc")
        s.wait_idle(0.6)
        check.ok("Esc closes the browser", s.view.find("open/close, ") is None, s.view.text())
        s.exit_quit()
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
