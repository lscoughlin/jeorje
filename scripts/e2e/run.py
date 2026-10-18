#!/usr/bin/env python3
"""Run end-to-end scenarios: scripts/e2e/run.py [name...]

Each scenario is scripts/e2e/scenarios/<name>.py with a function run(check) where check is a harness.Check.
Scenarios may define `build(env)` to receive the harness module. Missing pyte is installed into .jeorje/venv.
Exit code 0 means pass or skipped; 1 means a failure.
"""
import importlib.util
import os
import subprocess
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
VENV = os.path.join(ROOT, ".jeorje", "venv")
VENV_PY = os.path.join(VENV, "bin", "python")


def have_pyte(py):
    return subprocess.run([py, "-I", "-c", "import pyte"], capture_output=True).returncode == 0


def ensure_pyte():
    """Return True when pyte is importable in this process (re-executing under the venv when needed)."""
    try:
        import pyte  # noqa: F401
        return True
    except ImportError:
        pass
    if os.environ.get("JEORJE_E2E_REEXEC") != "1":
        if not (os.path.exists(VENV_PY) and have_pyte(VENV_PY)):
            if not os.path.exists(VENV_PY):
                subprocess.run([sys.executable, "-m", "venv", VENV], capture_output=True)
            if os.path.exists(VENV_PY):
                subprocess.run([VENV_PY, "-m", "pip", "install", "-q", "pyte"], capture_output=True, timeout=300)
        if os.path.exists(VENV_PY) and have_pyte(VENV_PY):
            env = dict(os.environ, JEORJE_E2E_REEXEC="1")
            os.execve(VENV_PY, [VENV_PY, os.path.abspath(__file__)] + sys.argv[1:], env)
    return False


def load(path):
    spec = importlib.util.spec_from_file_location("scenario_" + os.path.basename(path)[:-3], path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    if not ensure_pyte():
        print("SKIPPED: pyte is not available and could not be installed into " + VENV)
        return 0
    sys.path.insert(0, HERE)
    import harness
    if not os.path.exists(harness.JAR):
        print("FAIL: jar not found: %s (build: mvn -q -o -pl jeorje-cli -am package -DskipTests)" % harness.JAR)
        return 2
    sdir = os.path.join(HERE, "scenarios")
    all_names = sorted(f[:-3] for f in os.listdir(sdir) if f.endswith(".py") and not f.startswith("_"))
    names = sys.argv[1:] or all_names
    unknown = [n for n in names if n not in all_names]
    if unknown:
        print("FAIL: unknown scenario(s): " + ", ".join(unknown))
        return 2
    failed = []
    for name in names:
        check = harness.Check()
        start = time.time()
        print("RUN  " + name)
        try:
            load(os.path.join(sdir, name + ".py")).run(check)
        except Exception:
            traceback.print_exc()
            check.failures.append("exception")
        status = "FAIL" if check.failures else "PASS"
        print("%s %s (%.1fs)%s" % (status, name, time.time() - start,
                                   " - " + ", ".join(check.failures) if check.failures else ""))
        if check.failures:
            failed.append(name)
    print("RESULT: " + ("FAILED (%s)" % ", ".join(failed) if failed else "PASSED"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
