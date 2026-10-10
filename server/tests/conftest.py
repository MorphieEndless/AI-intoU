"""Shared pytest setup: environment isolation BEFORE any server import.

config.py reads environment variables at import time, so all env setup
must happen here (conftest is imported before test modules).
"""
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("SB_SECRET_KEY", "test-secret-key-for-pytest-only-0123456789abcdef")
_TMP = tempfile.mkdtemp(prefix="ai-intou-test-")
os.environ.setdefault("SB_DB_PATH", os.path.join(_TMP, "test.db"))
os.environ.setdefault("SB_PATTERNS_DIR", os.path.join(_TMP, "patterns"))

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))


import uuid  # noqa: E402

import pytest  # noqa: E402

TEST_PASSWORD = "correct-horse-battery"


def _account(admin: bool = False, password: str = TEST_PASSWORD, username: str | None = None) -> dict:
    """Create an account in the shared test database (schema brought to head)."""
    from app.db import session_scope, upgrade_to_head
    from app.domain.identity import create_user

    upgrade_to_head()
    with session_scope() as s:
        user = create_user(s, username or f"u{uuid.uuid4().hex[:10]}", password, is_admin=admin)
        return {"user_id": user.id, "username": user.username, "password": password}


def _mint(user_id: str, kind: str, name: str = "test") -> str:
    from app.db import session_scope
    from app.domain.identity import mint_token, require_user

    with session_scope() as s:
        plaintext, _ = mint_token(s, require_user(s, user_id=user_id), name=name, kind=kind)
        return plaintext


@pytest.fixture()
def make_account():
    return _account


@pytest.fixture()
def mint():
    return _mint


def phone_token_for(user_id: str, username: str | None = None) -> str:
    """Phone token for an account with a *fixed* id (creating it if needed).

    Library tests address stores by readable ids like "alice"; the REST layer
    now authenticates real accounts, so the account must exist.
    """
    from app.db import session_scope, upgrade_to_head
    from app.domain.identity import hash_password
    from app.models import User

    upgrade_to_head()
    with session_scope() as s:
        if s.get(User, user_id) is None:
            s.add(User(id=user_id, username=username or f"{user_id}-{uuid.uuid4().hex[:6]}",
                       password_hash=hash_password(TEST_PASSWORD), is_admin=0, is_active=1))
    return _mint(user_id, "phone", "library-test")


@pytest.fixture(autouse=True)
def _fresh_rate_limits():
    """Auth endpoints allow 5 attempts/minute and ban after 20 failures per
    IP; every TestClient request comes from the same IP. Isolate tests."""
    from app.core.rate_limit import ip_tracker, rate_limiter

    def reset():
        ip_tracker._failures.clear()
        ip_tracker._bans.clear()
        rate_limiter._windows.clear()

    reset()
    yield
    reset()
