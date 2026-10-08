"""Legacy compatibility shim — authentication no longer lives here.

Accounts, sessions and API tokens: `app.domain.identity` + `app.core.security`
(the single resolver, architecture doc §5). Rate limiting and IP bans:
`app.core.rate_limit`. The static bearer token, the legacy long-lived JWT
and the OAuth module were removed in M2b (01-product.md D1/D4).

What remains are the per-user safety-config helpers the legacy relay still
calls, now backed by the ORM instead of raw sqlite3.
"""
from __future__ import annotations

from app.core.rate_limit import IPBanTracker, RateLimiter, ip_tracker, rate_limiter
from app.db import session_scope
from app.domain.identity import get_safety_overrides, set_safety_overrides

__all__ = [
    "IPBanTracker", "RateLimiter", "ip_tracker", "rate_limiter",
    "get_safety_config", "set_safety_config",
]


def get_safety_config(user_id: str) -> dict:
    """Per-user overrides only (NULL columns omitted, bool kept as bool)."""
    with session_scope() as s:
        return get_safety_overrides(s, user_id)


def set_safety_config(user_id: str, overrides: dict) -> dict:
    """Upsert overrides; returns the stored overrides."""
    with session_scope() as s:
        return set_safety_overrides(s, user_id, overrides)
