"""Compatibility exports; runtime ownership lives in app.infra."""
from app.infra.session_registry import PhoneSession, SessionRegistry, WebSocketLike, registry

__all__ = ["PhoneSession", "SessionRegistry", "WebSocketLike", "registry"]
