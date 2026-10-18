"""The editor in the real application: /edit, typing, selection, cut and paste, undo and redo, find, save,
syntax colors for Java, injected Java colors in a Markdown fence, and the return to the chat."""
import re

import harness

JAVA = 'public class Foo {\n    // comment\n    String s = "text";\n    int n = 42;\n}\n'
MARKDOWN = '# Title\n\nSome text.\n\n```java\npublic class Bar { int x = 1; }\n```\n\n```python\ndef f(): pass\n```\n'

SHIFT_RIGHT = b"\x1b[1;2C"
SHIFT_DOWN = b"\x1b[1;2B"
SHIFT_END = b"\x1b[1;2F"
SHIFT_DEL = b"\x1b[3;2~"
CTRL_INS = b"\x1b[2;5~"
SHIFT_INS = b"\x1b[2;2~"
CTRL_HOME = b"\x1b[1;5H"
CTRL_END = b"\x1b[1;5F"


def modes(raw):
    return re.findall(rb"\x1b\[\?([0-9;]+)([hl])", raw)


def row_text(s, row):
    """Text of a screen row inside the editor frame (without the border and the scroll bar)."""
    return s.view.line(row)[1:-1].rstrip()


def raw_key(s, data):
    s.send_bytes(data)
    s.pump(0.15)


def fg(s, row, col):
    return s.view.cell(row, col).fg


def run(check):
    env = harness.Env(mouse=True)
    try:
        p = env.project
        p.write("Foo.java", JAVA)
        p.write("notes.md", MARKDOWN)
        s = env.start(args=["--trust"])
        check.ok("started", s.wait_started(), s.view.text()[-300:])
        s.wait_idle(1.0)

        # --- open with /edit ---
        mark = len(s.raw)
        s.send("/edit Foo.java")
        s.key("Enter")
        check.ok("the editor opens", s.wait_for("Foo.java", 15) and s.wait_for("Ln 1", 10), s.view.text())
        s.wait_idle(0.8)
        opened = modes(s.raw[mark:])
        check.ok("mouse tracking of the chat is turned off", (b"1002", b"l") in opened, opened)
        check.ok("the editor shows the file", row_text(s, 2) == "public class Foo {", s.view.text())
        check.ok("the menu bar shows", s.view.find("File  ") is not None and s.view.find("Search") is not None,
                 s.view.line(0))
        check.ok("the status bar shows the keys", s.view.find("Ctrl-S=Save") is not None, s.view.text())
        check.ok("the chat status line is gone", s.view.find("peers 0") is None, s.view.line(s.rows - 1))

        # --- syntax colors for Java ---
        check.ok("keyword color", fg(s, 2, 1) == "cyan", fg(s, 2, 1))
        check.ok("keyword color end", fg(s, 2, 6) == "cyan" and fg(s, 2, 14) == "default", (fg(s, 2, 6), fg(s, 2, 14)))
        check.ok("comment color", fg(s, 3, 5) == "blue" and fg(s, 3, 12) == "blue", (fg(s, 3, 5), fg(s, 3, 12)))
        check.ok("string color", s.view.find('"text"') is not None
                 and fg(s, *[v for v in [s.view.find('"text"')]][0]) == "red", fg(s, 4, 16))
        check.ok("type color", fg(s, 5, 5) == "green", fg(s, 5, 5))
        check.ok("number color", fg(s, 5, 13) == "brown", fg(s, 5, 13))

        # --- typing and selecting ---
        raw_key(s, CTRL_HOME)
        s.send("// ")
        s.wait_idle(0.5)
        check.ok("typed text appears", row_text(s, 2) == "// public class Foo {", row_text(s, 2))
        check.ok("typed comment gets the comment color", fg(s, 2, 1) == "blue" and fg(s, 2, 8) == "blue",
                 (fg(s, 2, 1), fg(s, 2, 8)))
        check.ok("the title marks the change", "*" in s.view.line(1), s.view.line(1))
        s.key("Home")
        for _ in range(3):
            raw_key(s, SHIFT_RIGHT)
        s.wait_idle(0.4)
        sel = [s.view.cell(2, 1 + i) for i in range(3)]
        rest = s.view.cell(2, 5)
        check.ok("Shift-Right selects three characters",
                 all(c.reverse or c.bg != rest.bg for c in sel) and not (rest.reverse and rest.bg == sel[0].bg),
                 (sel, rest))

        # --- cut, paste, undo, redo ---
        raw_key(s, SHIFT_DEL)
        s.wait_idle(0.4)
        check.ok("Shift-Del cuts the selection", row_text(s, 2) == "public class Foo {", row_text(s, 2))
        check.ok("the text is plain Java again", fg(s, 2, 1) == "cyan", fg(s, 2, 1))
        s.key("End")
        raw_key(s, SHIFT_INS)
        s.wait_idle(0.4)
        check.ok("Shift-Ins pastes", row_text(s, 2) == "public class Foo {//", row_text(s, 2))
        s.key("Ctrl-Z")
        s.wait_idle(0.4)
        check.ok("Ctrl-Z undoes the paste", row_text(s, 2) == "public class Foo {", row_text(s, 2))
        s.key("Ctrl-Y")
        s.wait_idle(0.4)
        check.ok("Ctrl-Y redoes the paste", row_text(s, 2) == "public class Foo {//", row_text(s, 2))
        # copy a line with the selection keys and paste it at the end of the file
        raw_key(s, CTRL_HOME)
        raw_key(s, SHIFT_END)
        raw_key(s, CTRL_INS)
        raw_key(s, CTRL_END)
        raw_key(s, SHIFT_INS)
        s.wait_idle(0.4)
        check.ok("Ctrl-Ins copies and Shift-Ins pastes the copy", row_text(s, 7) == "public class Foo {//",
                 s.view.text())
        s.key("Ctrl-Z")
        s.wait_idle(0.3)
        check.ok("undo removes the pasted copy", row_text(s, 7) == "", s.view.text())

        # --- find ---
        raw_key(s, CTRL_HOME)
        s.key("Ctrl-Q f")
        check.ok("the find dialog opens", s.wait_for("Find", 5), s.view.text())
        s.send("int n")
        s.key("Enter")
        s.wait_idle(0.6)
        check.ok("find moves the cursor to line 4", s.view.find("Ln 4") is not None, s.view.line(s.rows - 1))

        # --- save ---
        s.key("Ctrl-S")
        s.wait_idle(0.8)
        saved = p.read("Foo.java")
        check.ok("Ctrl-S writes the file", saved.startswith("public class Foo {// \n"), repr(saved[:60]))
        check.ok("the saved file keeps the other lines", '    int n = 42;\n}\n' in saved, repr(saved))
        check.ok("the title shows no change mark", "*" not in s.view.line(1), s.view.line(1))

        # --- back to the chat ---
        mark = len(s.raw)
        s.key("Alt-x")
        check.ok("Alt-X returns to the chat", s.wait_for("peers", 10) and s.view.find("Ln 4") is None, s.view.text())
        s.wait_idle(0.8)
        closed = modes(s.raw[mark:])
        check.ok("the alternate screen is entered again", closed.count((b"1049", b"h")) >= 1, closed)
        last_alt = max([i for i, m in enumerate(closed) if m[0] == b"1049"] or [-1])
        check.ok("the last alternate screen mode is on", last_alt >= 0 and closed[last_alt] == (b"1049", b"h"), closed)
        check.ok("mouse tracking is on again", closed[-12:].count((b"1002", b"h")) >= 1
                 and (b"1006", b"h") in closed[last_alt:], closed)
        check.ok("the editor frame is gone", s.view.find("Ctrl-S=Save") is None, s.view.text())
        check.ok("the chat view is drawn again", s.view.find("Jeorje is ready") is not None, s.view.text())

        # --- the chat works again ---
        s.send("/help")
        s.key("Enter")
        check.ok("the chat takes input after the editor", s.wait_for("/quit", 10) or s.wait_for("/agents", 5),
                 s.view.text())

        # --- Markdown with a fenced Java block ---
        s.send("/edit notes.md")
        s.key("Enter")
        check.ok("the Markdown file opens", s.wait_for("notes.md", 15) and s.wait_for("# Title", 10), s.view.text())
        s.wait_idle(0.8)
        check.ok("heading color", fg(s, 2, 1) == "brightmagenta", fg(s, 2, 1))
        check.ok("fence marker color", fg(s, 6, 1) == "magenta", fg(s, 6, 1))
        check.ok("Java keyword inside the fence is colored", fg(s, 7, 1) == "cyan" and fg(s, 7, 6) == "cyan",
                 (fg(s, 7, 1), fg(s, 7, 6)))
        check.ok("Java identifier inside the fence is plain", fg(s, 7, 14) == "default", fg(s, 7, 14))
        check.ok("the python fence has Python colors", fg(s, 11, 1) == "brightblue", fg(s, 11, 1))
        check.ok("ordinary text stays plain", fg(s, 4, 1) == "default", fg(s, 4, 1))
        s.key("Alt-x")
        check.ok("Alt-X leaves the Markdown file", s.wait_for("peers", 10), s.view.text())
        s.key("Ctrl-D")
        if s.exit_code(10) is None:
            s.key("Ctrl-D")
            s.exit_code(10)
        check.ok("terminal restored", s.terminal_restored())
    finally:
        env.close()

    # --- cut and paste with keys from the config ---
    env = harness.Env()
    try:
        env.project.config_yaml('editor:\n  keys:\n    cut: "Ctrl-W"\n    paste: "Ctrl-P"\n')
        env.project.write("Foo.java", JAVA)
        s = env.start(args=["--trust"])
        check.ok("started with editor keys", s.wait_started(), s.stream_text()[-300:])
        s.wait_idle(1.0)
        s.send("/edit Foo.java")
        s.key("Enter")
        check.ok("the editor opens (config keys)", s.wait_for("Ln 1", 15), s.view.text())
        for _ in range(6):
            raw_key(s, SHIFT_RIGHT)
        s.key("Ctrl-W")
        s.wait_idle(0.4)
        check.ok("the configured cut key cuts", row_text(s, 2) == " class Foo {", row_text(s, 2))
        s.key("End")
        s.key("Ctrl-P")
        s.wait_idle(0.4)
        check.ok("the configured paste key pastes", row_text(s, 2) == " class Foo {public", row_text(s, 2))
        raw_key(s, SHIFT_DEL)
        s.wait_idle(0.3)
        check.ok("the default cut key no longer cuts", row_text(s, 2) == " class Foo {public", row_text(s, 2))
    finally:
        env.close()
