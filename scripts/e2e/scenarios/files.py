"""The file browser app: /files, Up and Down, Right and Left, Enter on a file and on a directory, Esc, the list
form, and an @path mention."""
import harness

README = "OPENED-BY-EDITOR\n"


def run(check):
    env = harness.Env()
    try:
        p = env.project
        # The app keeps its state in .jeorje/, so the ignore rules hide that directory from the tree.
        p.write(".gitignore", ".jeorje/\n")
        p.write("alpha.txt", "alpha\n")
        p.write("notes.md", "notes\n")
        p.write("src/Gamma.java", "class Gamma {}\n")
        p.write("src/Deep.java", "class Deep {}\n")
        s = env.start(args=["--trust"])
        check.ok("started", s.wait_started(), s.view.text()[-300:])
        s.wait_idle(1.0)

        # /files opens the pane
        s.send("/files")
        s.key("Enter")
        check.ok("the pane opens", s.wait_for("open/close, ", 10), s.view.text())
        check.ok("the tree lists alpha.txt", s.view.find("alpha.txt") is not None, s.view.text())
        check.ok("the tree lists src/", s.view.find("src/") is not None, s.view.text())
        check.ok("the tree lists notes.md", s.view.find("notes.md") is not None, s.view.text())
        tree = s.view.text()
        check.ok("src/ comes before the files", 0 <= tree.find("src/") < tree.find("alpha.txt"), s.view.text())
        check.ok("the keys row shows", s.view.find("Up/Down move") is not None, s.view.text())

        # Down and Up move the selection
        check.ok("src/ is selected first", s.view.find("*> src/") is not None, s.view.text())
        s.key("Down")
        s.wait_idle(0.3)
        check.ok("Down selects the next row", s.view.find("*  .gitignore") is not None, s.view.text())
        s.key("Down")
        s.wait_idle(0.3)
        check.ok("Down again selects a file", s.view.find("*  alpha.txt") is not None, s.view.text())
        s.key("Up")
        s.wait_idle(0.3)
        check.ok("Up goes back to the row above", s.view.find("*  .gitignore") is not None, s.view.text())
        s.key("Up")
        s.wait_idle(0.3)
        check.ok("Up returns to src/", s.view.find("*> src/") is not None, s.view.text())

        # Right opens the directory and moves in
        s.key("Right")
        s.wait_idle(0.4)
        check.ok("Right opens src/", s.view.find("v src/") is not None, s.view.text())
        check.ok("Right moves into src/", s.view.find("*    Deep.java") is not None, s.view.text())
        check.ok("the children come before Gamma.java",
                 s.view.find("Deep.java") is not None and s.view.find("Gamma.java") is not None
                 and s.view.text().find("Deep.java") < s.view.text().find("Gamma.java"), s.view.text())

        # Left from a child moves to the parent row
        s.key("Left")
        s.wait_idle(0.4)
        check.ok("Left from a file moves to the parent row", s.view.find("*v src/") is not None, s.view.text())
        check.ok("the parent directory stays open", s.view.find("v src/") is not None, s.view.text())

        # Left on the open directory closes it and keeps the selection
        s.key("Left")
        s.wait_idle(0.4)
        check.ok("Left closes src/", s.view.find("v src/") is None
                 and s.view.find("> src/") is not None, s.view.text())
        check.ok("Left keeps the selection on src/", s.view.find("*> src/") is not None, s.view.text())

        # Enter on a directory expands it and the pane stays open
        s.key("Enter")
        s.wait_idle(0.4)
        check.ok("Enter expands src/", s.view.find("v src/") is not None, s.view.text())
        check.ok("the pane stays open", s.view.find("open/close, ") is not None, s.view.text())
        s.key("Right")
        s.wait_idle(0.3)
        check.ok("Right on an open directory moves in", s.view.find("*    Deep.java") is not None, s.view.text())

        # Enter on a file opens the editor
        s.key("Enter")
        check.ok("Enter on a file opens the editor", s.wait_for("Ln 1", 10), s.view.text())
        check.ok("the editor shows Deep.java", s.view.find("Deep.java") is not None, s.view.text())
        check.ok("the editor shows the text", s.view.find("class Deep {}") is not None, s.view.text())
        s.key("Alt-x")
        s.wait_idle(0.8)
        check.ok("Alt-X returns to the chat", s.view.find("Ln 1") is None and s.view.find("open/close, ") is None,
                 s.view.text())

        # Esc closes the pane
        s.key("Ctrl-X f")
        check.ok("the pane opens again", s.wait_for("open/close, ", 10), s.view.text())
        s.key("Esc")
        s.wait_idle(0.6)
        check.ok("Esc closes the pane", s.view.find("open/close, ") is None, s.view.text())

        # /files list prints the tree
        s.send("/files list")
        s.key("Enter")
        check.ok("the list prints the tree", s.wait_for("Files in ", 10) and s.view.find("alpha.txt") is not None,
                 s.view.text())
        check.ok("the list names the hint", s.view.find("/edit <path>") is not None, s.view.text())
        s.send("/files list gam")
        s.key("Enter")
        check.ok("the list takes a filter", s.wait_for("(filter: ", 10) and s.view.find("Gamma.java") is not None,
                 s.view.text())
        s.wait_idle(0.5)

        # an @path mention adds the file to the next message
        before = len(env.server.requests)
        p.write("README.md", README)
        s.send("read @README.md now")
        s.key("Enter")
        check.ok("the mention sends a request", s.wait_for_output("OPENED-BY-EDITOR", 20)
                 or len(env.server.requests) > before, s.view.text())
        sent = ""
        for r in env.server.requests[before:]:
            sent = harness.content_text([m for m in r.get("messages", []) if m.get("role") == "user"][-1]
                                        .get("content"))
            if "@README.md" in sent:
                break
        check.ok("the request has the Files line", "Files: README.md" in sent, sent)
        check.ok("the request keeps the mention", "@README.md" in sent, sent)
        s.wait_idle(0.8)

        s.exit_quit()
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()
