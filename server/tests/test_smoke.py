"""Import & compile smoke gate — the K1 lesson.

A stray backslash once landed in app.py and made the whole package
unimportable while every existing test still passed (none imported the
full app). These tests run FIRST in CI: if anything here fails, the
rest of the suite is meaningless.
"""
import compileall
import importlib
import pkgutil
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent.parent / "server"


def test_all_sources_compile():
    ok = compileall.compile_dir(str(PACKAGE_DIR), quiet=1)
    assert ok, "py_compile failed for at least one module in server/"


def test_package_modules_importable():
    import server  # noqa: F401
    failures = []
    for mod in pkgutil.iter_modules([str(PACKAGE_DIR)]):
        name = f"server.{mod.name}"
        try:
            importlib.import_module(name)
        except Exception as exc:  # noqa: BLE001 — report every failure
            failures.append(f"{name}: {exc!r}")
    assert not failures, "import failures:\n" + "\n".join(failures)


def test_app_routes_registered():
    from server.app import app
    paths = {getattr(r, "path", None) for r in app.routes}
    for expected in ("/mcp", "/ws/phone", "/health",
                     "/auth/register", "/auth/login",
                     "/safety/config", "/safety/status"):
        assert expected in paths, f"missing route: {expected}"
