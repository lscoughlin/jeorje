"""End-to-end harness: runs the real jeorje jar in a pseudo-terminal against a scripted stub chat server.

Parts: StubServer (scripted OpenAI-compatible server), Project (temporary project directory),
Session (the jar in a pseudo-terminal with a pyte screen).
"""
import fcntl
import json
import os
import pty
import select
import shutil
import signal
import struct
import sys
import subprocess
import tempfile
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, "jeorje-cli", "target", "jeorje.jar")


def tool_call(name, arguments, call_id=None):
    """A scripted tool call. `arguments` is a dict or a JSON string."""
    return {"name": name, "arguments": arguments if isinstance(arguments, str) else json.dumps(arguments),
            "id": call_id}


class StubServer:
    """OpenAI-compatible /chat/completions server with scripted responses.

    A scripted response is a text (str), a list of tool calls (see tool_call), a dict
    {"text": ..., "tool_calls": [...], "delay": seconds}, or a function(request_body) returning one of these.
    Responses are served in order. When the script is empty, `default` is used.
    `chooser`, when set, is called as chooser(body) and may return a response or None (fall through to the script).
    Every request body is recorded in `requests` (parsed JSON), in order.
    """

    def __init__(self, responses=None, default="STUBANSWER42 hello from the stub", chooser=None):
        self.script = list(responses or [])
        self.default = default
        self.chooser = chooser
        self.requests = []
        self.lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.send_response(404)
                self.end_headers()

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                try:
                    body = json.loads(raw or b"{}")
                except ValueError:
                    body = {"_raw": raw.decode("utf8", "replace")}
                outer._serve(self, body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    @property
    def base_url(self):
        return "http://127.0.0.1:%d/v1" % self.server.server_address[1]

    def add(self, *responses):
        with self.lock:
            self.script.extend(responses)

    def models(self):
        return [r.get("model") for r in self.requests]

    def last_user_text(self, body=None):
        body = body or (self.requests[-1] if self.requests else {})
        for m in reversed(body.get("messages", [])):
            if m.get("role") == "user":
                return content_text(m.get("content"))
        return ""

    def requests_containing(self, text):
        return [r for r in self.requests if text in json.dumps(r)]

    def _pick(self, body):
        with self.lock:
            self.requests.append(body)
            chosen = None
            if self.chooser:
                chosen = self.chooser(body)
            if chosen is None:
                chosen = self.script.pop(0) if self.script else self.default
        if callable(chosen):
            chosen = chosen(body)
        return chosen

    def _serve(self, h, body):
        resp = self._pick(body)
        text, calls, delay = "", [], 0
        if isinstance(resp, str):
            text = resp
        elif isinstance(resp, list):
            calls = resp
        elif isinstance(resp, dict):
            text, calls, delay = resp.get("text", ""), resp.get("tool_calls", []), resp.get("delay", 0)
        h.send_response(200)
        h.send_header("Content-Type", "text/event-stream")
        h.send_header("Connection", "close")
        h.end_headers()
        model = body.get("model", "stub")

        def chunk(delta, finish=None, usage=None):
            data = {"id": "stub-1", "object": "chat.completion.chunk", "created": 0, "model": model,
                    "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            if usage:
                data["usage"] = usage
            h.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
            h.wfile.flush()

        try:
            chunk({"role": "assistant", "content": ""})
            if delay:
                time.sleep(delay)
            if text:
                words = text.split(" ")
                for i, w in enumerate(words):
                    chunk({"content": w + (" " if i < len(words) - 1 else "")})
            for i, c in enumerate(calls):
                chunk({"tool_calls": [{"index": i, "id": c.get("id") or "call_%d_%d" % (len(self.requests), i),
                                       "type": "function",
                                       "function": {"name": c["name"], "arguments": c["arguments"]}}]})
            chunk({}, "tool_calls" if calls else "stop",
                  {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15})
            h.wfile.write(b"data: [DONE]\n\n")
            h.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        h.close_connection = True


def content_text(content):
    if isinstance(content, list):
        return "".join(p.get("text", "") for p in content if isinstance(p, dict))
    return content or ""


class Project:
    """A temporary project directory."""

    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="jeorje-e2e-")
        self.work = os.path.join(self.root, "work")
        self.home = os.path.join(self.root, "home")
        os.makedirs(self.work)
        os.makedirs(self.home)

    def path(self, rel):
        return os.path.join(self.work, rel)

    def write(self, rel, content=""):
        p = self.path(rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(content)
        return p

    def read(self, rel):
        with open(self.path(rel)) as f:
            return f.read()

    def agents_md(self, content):
        return self.write("AGENTS.md", content)

    def skill(self, name, body, description="A test skill."):
        return self.write(".agents/skills/%s/SKILL.md" % name,
                          "---\nname: %s\ndescription: %s\n---\n%s\n" % (name, description, body))

    def config_yaml(self, content):
        return self.write(".jeorje/config.yaml", content)

    def git_init(self):
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t",
                   GIT_COMMITTER_EMAIL="t@t")
        subprocess.run(["git", "init", "-q"], cwd=self.work, check=True, env=env)
        subprocess.run(["git", "add", "-A"], cwd=self.work, env=env)
        subprocess.run(["git", "commit", "-q", "-m", "init", "--allow-empty"], cwd=self.work, env=env)

    def cleanup(self):
        shutil.rmtree(self.root, ignore_errors=True)


KEYS = {
    "Enter": b"\r", "Esc": b"\x1b", "Tab": b"\t", "Shift-Tab": b"\x1b[Z", "Backspace": b"\x7f", "Space": b" ",
    "Up": b"\x1b[A", "Down": b"\x1b[B", "Right": b"\x1b[C", "Left": b"\x1b[D", "Home": b"\x1b[H", "End": b"\x1b[F",
    "PageUp": b"\x1b[5~", "PageDown": b"\x1b[6~", "Delete": b"\x1b[3~", "Insert": b"\x1b[2~",
    "F1": b"\x1bOP", "F2": b"\x1bOQ", "F3": b"\x1bOR", "F4": b"\x1bOS", "F5": b"\x1b[15~", "F6": b"\x1b[17~",
    "F7": b"\x1b[18~", "F8": b"\x1b[19~", "F9": b"\x1b[20~", "F10": b"\x1b[21~", "F11": b"\x1b[23~",
    "F12": b"\x1b[24~",
}


def key_bytes(name):
    """Bytes for a named key. A chord is several keys with spaces, for example 'Ctrl-X a'."""
    out = b""
    for part in name.split(" "):
        if part in KEYS:
            out += KEYS[part]
        elif part.startswith("Ctrl-") and len(part) == 6:
            out += bytes([ord(part[5].upper()) & 0x1f])
        elif part.startswith("Alt-") and len(part) == 5:
            out += b"\x1b" + part[4].encode()
        else:
            out += part.encode()
    return out


class Cell:
    def __init__(self, char):
        self.char = char.data
        self.fg = char.fg
        self.bg = char.bg
        self.bold = char.bold
        self.reverse = char.reverse
        self.italics = char.italics
        self.underscore = char.underscore

    def __repr__(self):
        return "Cell(%r fg=%s bg=%s bold=%s reverse=%s)" % (self.char, self.fg, self.bg, self.bold, self.reverse)


class ScreenView:
    def __init__(self, session):
        self.session = session

    def text(self):
        self.session._drain()
        return "\n".join(line.rstrip() for line in self.session.screen.display)

    def line(self, n):
        self.session._drain()
        return self.session.screen.display[n].rstrip()

    def cell(self, row, col):
        self.session._drain()
        return Cell(self.session.screen.buffer[row][col])

    def find(self, text):
        """(row, col) of the first match of text on the screen, or None."""
        self.session._drain()
        for r, line in enumerate(self.session.screen.display):
            c = line.find(text)
            if c >= 0:
                return r, c
        return None


class Session:
    """The jar in a pseudo-terminal of a given size."""

    def __init__(self, project, server, rows=30, cols=100, args=None, env=None, jar=None, mouse=False):
        import pyte
        self.project, self.server, self.rows, self.cols = project, server, rows, cols
        self.screen = pyte.Screen(cols, rows)
        self.stream = pyte.ByteStream(self.screen)
        self.view = ScreenView(self)
        self.raw = b""
        self._fed = 0
        jar = jar or JAR
        with open(os.path.join(project.home, "config.json"), "w") as f:
            json.dump({"llm": {"baseUrl": server.base_url, "apiKeyEnv": "OPENROUTER_API_KEY"}}, f)
        e = dict(os.environ, TERM="xterm-256color", JEORJE_HOME=project.home, OPENROUTER_API_KEY="stub-key",
                 HOME=project.root, LINES=str(rows), COLUMNS=str(cols))
        e.pop("NO_COLOR", None)
        e.update(env or {})
        java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
        argv = [java, "-Duser.home=" + project.root, "-jar", jar, "--base-url", server.base_url, "--yes"]
        if not mouse:
            argv.append("--no-mouse")
        argv += list(args or [])
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.slave = slave
        self.attrs_before = termios.tcgetattr(slave)
        self.proc = subprocess.Popen(argv, stdin=slave, stdout=slave, stderr=slave, env=e, cwd=project.work,
                                     start_new_session=True, preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0))
        self.screen_view = self.view

    # --- output ---
    def _read(self, seconds):
        end = time.time() + seconds
        while True:
            left = end - time.time()
            if left <= 0:
                return
            r, _, _ = select.select([self.master], [], [], min(left, 0.1))
            if r:
                try:
                    data = os.read(self.master, 65536)
                except OSError:
                    return
                if not data:
                    return
                self.raw += data

    def _drain(self):
        self._read(0.0)
        if self._fed < len(self.raw):
            self.stream.feed(self.raw[self._fed:])
            self._fed = len(self.raw)

    def raw_output(self):
        self._drain()
        return self.raw

    def pump(self, seconds):
        self._read(seconds)
        self._drain()

    def wait_for(self, text, timeout=15):
        end = time.time() + timeout
        while time.time() < end:
            self.pump(0.15)
            if self.view.find(text) is not None:
                return True
            if self.proc.poll() is not None:
                self.pump(0.3)
                return self.view.find(text) is not None
        return self.view.find(text) is not None

    def stream_text(self):
        """All output as text with escape sequences removed (words split by cursor moves still match)."""
        import re
        raw = re.sub(rb"\x1b\[[0-9;?<>=$]*[ -/]*[@-~]", b"", self.raw_output())
        raw = re.sub(rb"\x1b\][^\x07\x1b]*(\x07|\x1b\\)", b"", raw)
        return re.sub(rb"\x1b[()][0-9A-Za-z]", b"", raw).decode("utf8", "replace")

    def wait_for_output(self, text, timeout=15):
        """Wait until `text` was written to the terminal at any time (it need not be visible now)."""
        end = time.time() + timeout
        while time.time() < end:
            self.pump(0.15)
            if text in self.stream_text():
                return True
            if self.proc.poll() is not None:
                self.pump(0.3)
                break
        return text in self.stream_text()

    def wait_idle(self, quiet=0.6, timeout=15):
        """Wait until no output arrives for `quiet` seconds."""
        end = time.time() + timeout
        last = len(self.raw)
        stable = time.time()
        while time.time() < end:
            self.pump(0.1)
            if len(self.raw) != last:
                last = len(self.raw)
                stable = time.time()
            elif time.time() - stable >= quiet:
                return True
        return False

    # --- input ---
    def send_bytes(self, data):
        os.write(self.master, data)

    def send(self, text):
        self.send_bytes(text.encode())

    def key(self, *names):
        for n in names:
            self.send_bytes(key_bytes(n))
            self.pump(0.05)

    def mouse(self, button, col, row, press=True):
        """SGR mouse event. col and row are 0-based screen cells; button is the SGR code (0 left, 2 right)."""
        self.send_bytes(("\x1b[<%d;%d;%d%s" % (button, col + 1, row + 1, "M" if press else "m")).encode())
        self.pump(0.05)

    def click(self, col, row, button=0):
        self.mouse(button, col, row, True)
        self.mouse(button, col, row, False)

    def drag(self, c1, r1, c2, r2, button=0):
        self.mouse(button, c1, r1, True)
        steps = max(abs(c2 - c1), abs(r2 - r1), 1)
        for i in range(1, steps + 1):
            self.mouse(button + 32, c1 + (c2 - c1) * i // steps, r1 + (r2 - r1) * i // steps, True)
        self.mouse(button, c2, r2, False)

    def wheel(self, col, row, up=True, n=1):
        for _ in range(n):
            self.mouse(64 if up else 65, col, row, True)

    # --- state ---
    def alive(self):
        return self.proc.poll() is None

    def exit_code(self, timeout=15):
        end = time.time() + timeout
        while time.time() < end and self.proc.poll() is None:
            self.pump(0.2)
        self.pump(0.3)
        return self.proc.poll()

    def exit_quit(self, script_wait=1.0, timeout=15):
        """/quit, then wait for the exit. Returns the exit code or None."""
        self.send("/quit")
        self.key("Enter")
        self.wait_idle(script_wait)
        return self.exit_code(timeout)

    def terminal_restored(self):
        try:
            after = termios.tcgetattr(self.master if sys.platform == "darwin" else self.slave)
        except termios.error:
            return False
        return after == self.attrs_before

    def entered_alt_screen(self):
        return b"\x1b[?1049h" in self.raw or b"\x1b[?47h" in self.raw

    def left_alt_screen(self):
        return b"\x1b[?1049l" in self.raw or b"\x1b[?47l" in self.raw

    def wait_started(self, timeout=40):
        end = time.time() + timeout
        while time.time() < end and not self.entered_alt_screen() and self.alive():
            self.pump(0.2)
        self.pump(1.0)
        return self.entered_alt_screen()

    def close(self):
        if self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            self.proc.wait()
        for fd in (self.master, self.slave):
            try:
                os.close(fd)
            except OSError:
                pass


class Check:
    """Collects named checks for a scenario. Scenarios call `ok` and the runner reads `failures`."""

    def __init__(self):
        self.failures = []

    def warn(self, name, cond, detail=""):
        """A check that is reported but does not fail the scenario."""
        print("    %s: %s%s" % ("ok  " if cond else "WARN", name, (" - " + str(detail)[:300]) if detail and not cond else ""))
        return cond

    def ok(self, name, cond, detail=""):
        print("    %s: %s%s" % ("ok  " if cond else "FAIL", name, (" - " + str(detail)) if detail and not cond else ""))
        if not cond:
            self.failures.append(name)
        return cond


class Env:
    """Convenience: project, server, and a started session, with cleanup."""

    def __init__(self, responses=None, **session_args):
        self.project = Project()
        self.server = StubServer(responses).start()
        self.session_args = session_args
        self.session = None
        self.check = Check()

    def start(self, **more):
        args = dict(self.session_args)
        args.update(more)
        self.session = Session(self.project, self.server, **args)
        return self.session

    def close(self):
        if self.session:
            self.session.close()
        self.server.stop()
        self.project.cleanup()
