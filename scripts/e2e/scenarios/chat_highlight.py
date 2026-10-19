"""Code and diffs in the chat view have colors. NO_COLOR removes the colors."""
import harness

FENCES = (
    "Here is code:\n"
    "```java\n"
    "public class Demo {\n"
    "    int x = 1;\n"
    "}\n"
    "```\n"
    "And a plain block:\n"
    "```\n"
    "plainword stays plain\n"
    "```\n"
    "Now I read and edit.\n"
)


def script(project):
    project.write("Foo.java", "public class Foo {\n    int x = 1;\n}\n")
    return [
        {"text": FENCES, "tool_calls": [harness.tool_call("read", {"path": "Foo.java"}, "call_read")]},
        [harness.tool_call("edit", {"path": "Foo.java", "oldString": "int x = 1;", "newString": "int y = 2;"},
                           "call_edit")],
        "Finished the edit.",
    ]


def start(env_vars=None):
    env = harness.Env(env=env_vars) if env_vars else harness.Env()
    env.server.add(*script(env.project))
    return env


def drive(env):
    s = env.start()
    assert s.wait_started(), s.view.text()[-300:]
    s.send("please change Foo")
    s.key("Enter")
    s.wait_for_output("Finished the edit", 40)
    s.wait_idle(1.0)
    s.key("Ctrl-X o")  # expand the result of the last tool (edit)
    s.wait_idle(0.8)
    return s


def not_default(color):
    return color not in ("default", None)


def find_cell(view, text):
    pos = view.find(text)
    return (pos, view.cell(*pos)) if pos else (None, None)


def run(check):
    env = start()
    try:
        s = drive(env)
        text = s.view.text()
        check.ok("java block visible", "public class Demo" in text, text)
        pos, cell = find_cell(s.view, "public class Demo")
        check.ok("keyword public has a color", pos is not None and not_default(cell.fg), cell)
        if pos:
            ident = s.view.cell(pos[0], pos[1] + len("public class "))
            check.warn("identifier differs from keyword", ident.fg != cell.fg, (cell, ident))
        ppos, pcell = find_cell(s.view, "plainword")
        check.ok("untagged non-diff block is plain", ppos is not None and pcell.fg == "default", pcell)
        added = s.view.find("+    int y = 2;")
        removed = s.view.find("-    int x = 1;")
        check.ok("diff lines visible", added is not None and removed is not None, text)
        if added and removed:
            a = s.view.cell(added[0], added[1] + 1)
            r = s.view.cell(removed[0], removed[1] + 1)
            # the marker column holds the tint; the text after it keeps the tint under the syntax colors
            a_bg = s.view.cell(added[0], added[1]).bg
            r_bg = s.view.cell(removed[0], removed[1]).bg
            check.ok("added and removed lines have different tints",
                     a_bg != r_bg and (not_default(a_bg) or not_default(r_bg)), (a_bg, r_bg, a, r))
            check.ok("added line is tinted", not_default(a_bg), a_bg)
            check.ok("removed line is tinted", not_default(r_bg), r_bg)
        s.exit_quit()
    finally:
        env.close()

    env = start({"NO_COLOR": "1"})
    try:
        s = drive(env)
        text = s.view.text()
        check.ok("no color: java block visible", "public class Demo" in text, text)
        colored = []
        for row in range(0, s.rows - 2):
            for col in range(s.cols):
                c = s.view.cell(row, col)
                if c.char.strip() and (not_default(c.fg) or not_default(c.bg)):
                    colored.append((row, col, c))
        check.ok("NO_COLOR removes the colors from the chat view", not colored, colored[:3])
        s.exit_quit()
    finally:
        env.close()
