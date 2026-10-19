"""Wheel scroll, follow notice, scroll bar, selection, and copy (OSC 52) in the chat view."""
import base64
import re

import harness

LINES = "\n".join("row%02d text" % i for i in range(1, 81))
MORE = "\n".join("later%02d text" % i for i in range(1, 9))


def first_row(s):
    return s.view.line(0)


def osc52_texts(raw):
    out = []
    for m in re.finditer(rb"\x1b\]52;c;([A-Za-z0-9+/=]*)(?:\x07|\x1b\\)", raw):
        out.append(base64.b64decode(m.group(1)).decode("utf8", "replace"))
    return out


def run(check):
    env = harness.Env([LINES, MORE], mouse=True)
    try:
        s = env.start()
        check.ok("started", s.wait_started(), s.view.text()[-300:])
        check.ok("mouse tracking on", b"\x1b[?100" in s.raw, s.raw[-200:])
        s.send("go")
        s.key("Enter")
        check.ok("answer arrived", s.wait_for("row80 text", 40), s.view.text())
        s.wait_idle(1.0)
        check.ok("view follows the bottom", s.view.find("row80 text") is not None)
        before = first_row(s)

        # wheel up changes the first visible line
        s.wheel(10, 5, up=True, n=2)
        s.wait_idle(0.5)
        after = first_row(s)
        check.ok("wheel up changes the first line", after != before, (before, after))
        check.ok("bottom line left the view", s.view.find("row80 text") is None, s.view.text())
        s.wheel(10, 5, up=False, n=1)
        s.wait_idle(0.4)
        check.ok("wheel down scrolls back", first_row(s) != after, (after, first_row(s)))
        s.wheel(10, 5, up=True, n=1)
        s.wait_idle(0.4)

        # new text while scrolled up shows the notice
        s.send("more")
        s.key("Enter")
        check.ok("follow notice appears", s.wait_for("new lines", 30), s.view.text())
        check.ok("notice names the key and the click", s.view.find("Ctrl-X b or click here to follow") is not None,
                 s.view.text())
        check.ok("view did not jump to the bottom", s.view.find("later08 text") is None, s.view.text())

        # Ctrl-X b returns to the bottom
        s.key("Ctrl-X b")
        s.wait_idle(0.6)
        check.ok("Ctrl-X b shows the newest line", s.view.find("later08 text") is not None, s.view.text())
        check.ok("notice is gone", s.view.find("new lines") is None, s.view.text())

        # the scroll bar: the last column holds the bar; a click on the up arrow scrolls one line
        bar_col = s.cols - 2
        top = s.view.cell(0, bar_col).char
        check.ok("scroll bar up arrow", top == "▲", repr(top))
        s.wheel(10, 5, up=True, n=3)
        s.wait_idle(0.4)
        scrolled = first_row(s)
        s.click(bar_col, 0)
        s.wait_idle(0.4)
        check.ok("click on the up arrow scrolls one line", first_row(s) != scrolled, (scrolled, first_row(s)))
        # a click on the track above the thumb scrolls a page toward the click
        scrolled = first_row(s)
        s.click(bar_col, 3)
        s.wait_idle(0.4)
        check.ok("click on the track scrolls a page", first_row(s) != scrolled, (scrolled, first_row(s)))
        s.key("Ctrl-X b")
        s.wait_idle(0.5)

        # selection by drag, copy with Ctrl-X c, OSC 52 carries the text
        pos = s.view.find("later05 text")
        check.ok("target line visible", pos is not None, s.view.text())
        if pos:
            row, col = pos
            s.drag(col, row, col + 6, row)
            s.wait_idle(0.4)
            sel = [s.view.cell(row, col + i).reverse for i in range(7)]
            check.ok("selected cells are marked", all(sel), sel)
            s.key("Ctrl-X c")
            s.wait_idle(0.6)
            copied = osc52_texts(s.raw_output())
            check.ok("OSC 52 carries the selected text", "later05" in copied, copied)
            check.ok("copied text has no escape codes", all("\x1b" not in c for c in copied), copied)

        # double click selects a word, copy gives the word
        pos = s.view.find("later06 text")
        if pos:
            row, col = pos
            s.mouse(0, col + 2, row, True)
            s.mouse(0, col + 2, row, False)
            s.mouse(0, col + 2, row, True)
            s.mouse(0, col + 2, row, False)
            s.wait_idle(0.4)
            s.key("Ctrl-X c")
            s.wait_idle(0.6)
            copied = osc52_texts(s.raw_output())
            check.ok("double click selects a word", "later06" in copied, copied)

        s.exit_quit()
        check.ok("mouse tracking turned off at exit", b"\x1b[?1000l" in s.raw or b"\x1b[?1002l" in s.raw)
    finally:
        env.close()
