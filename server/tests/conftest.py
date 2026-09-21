"""Shared pytest setup: environment isolation BEFORE any server import.

config.py reads environment variables at import time, so all env setup
must happen here (conftest is imported before test modules).
"""
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("SB_SECRET_KEY", "test-secret-key-for-pytest-only")
os.environ.setdefault(
    "SB_STATIC_BEARER_TOKEN",
    "static-test-token-that-is-at-least-32-characters",
)
_TMP = tempfile.mkdtemp(prefix="ai-intou-test-")
os.environ.setdefault("SB_DB_PATH", os.path.join(_TMP, "test.db"))
os.environ.setdefault("SB_PATTERNS_DIR", os.path.join(_TMP, "patterns"))

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
