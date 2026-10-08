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


def _route_paths(routes) -> set[str]:
    """Collect paths, descending into included routers.

    Newer FastAPI wraps `include_router` in an object whose own `.path` is
    not the sub-route path, so a flat scan of `app.routes` misses them.
    """
    paths: set[str] = set()
    for route in routes:
        path = getattr(route, "path", None)
        if path:
            paths.add(path)
        for attr in ("routes", "router"):
            inner = getattr(route, attr, None)
            inner = getattr(inner, "routes", inner)
            if isinstance(inner, (list, tuple)):
                paths |= _route_paths(inner)
    return paths


def test_app_routes_registered():
    from fastapi.testclient import TestClient
    from server.app import app

    paths = _route_paths(app.routes)
    for expected in ("/mcp", "/ws/phone", "/health", "/safety/config", "/safety/status"):
        assert expected in paths, f"missing route: {expected}"

    # Included routers: prove they are mounted by hitting them, which works
    # regardless of how the framework represents them internally.
    with TestClient(app) as client:
        for method, path in (("POST", "/auth/register"), ("POST", "/auth/login"),
                             ("GET", "/api/me"), ("GET", "/api/tokens"),
                             ("GET", "/api/admin/users"), ("GET", "/api/admin/invites"),
                             ("GET", "/patterns")):
            resp = client.request(method, path, json={})
            assert resp.status_code not in (404, 405), f"missing route: {method} {path}"
