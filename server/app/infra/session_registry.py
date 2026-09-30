"""
Signal Bridge Remote — Session Registry

Maps authenticated users to their active phone WebSocket connections.
Handles routing commands from MCP tool calls to the correct phone.
"""
from __future__ import annotations
import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional, Any, Protocol, runtime_checkable

from app.schemas.ws import CommandAck

log = logging.getLogger("signal_bridge.sessions")


# ════════════════════════════════════════════════════════════════════════
# WebSocket Protocol — works with any WebSocket implementation
# (FastAPI wrapper, websockets library, etc.)
# ════════════════════════════════════════════════════════════════════════

@runtime_checkable
class WebSocketLike(Protocol):
    """Minimal interface for a WebSocket connection."""
    async def send(self, data: str) -> None: ...
    async def close(self, code: int = 1000, reason: str = "") -> None: ...


@dataclass
class PhoneSession:
    """An active connection from a user's phone."""
    user_id: str
    websocket: WebSocketLike
    connected_at: float = field(default_factory=time.time)
    last_heartbeat: float = field(default_factory=time.time)
    devices: list[dict[str, Any]] = field(default_factory=list)

    # Pending command acknowledgments: request_id → Future
    _pending: dict[str, asyncio.Future] = field(default_factory=dict)

    async def send_command(self, command: dict, timeout: float = 10.0) -> CommandAck:
        """Send a command and wait for acknowledgment."""
        request_id = str(uuid.uuid4())[:8]
        command["request_id"] = request_id

        loop = asyncio.get_running_loop()
        future: asyncio.Future[CommandAck] = loop.create_future()
        self._pending[request_id] = future

        try:
            await self.websocket.send(json.dumps(command))
            ack = await asyncio.wait_for(future, timeout=timeout)
            return ack
        except asyncio.TimeoutError:
            return CommandAck(success=False, message="Phone did not respond in time")
        finally:
            self._pending.pop(request_id, None)

    def resolve_ack(self, request_id: str, ack: CommandAck):
        """Called when the phone sends a command_ack."""
        future = self._pending.get(request_id)
        if future and not future.done():
            future.set_result(ack)

    async def send_fire_and_forget(self, command: dict):
        """Send without waiting for ack (used for heartbeats, stops)."""
        try:
            await self.websocket.send(json.dumps(command))
        except Exception:
            pass  # connection probably dead, heartbeat will catch it


class SessionRegistry:
    """
    Central registry mapping users to their active phone sessions.
    Thread-safe via asyncio locks.
    """

    def __init__(self):
        self._sessions: dict[str, PhoneSession] = {}  # user_id → session
        self._lock = asyncio.Lock()

    async def register(self, user_id: str, websocket: WebSocketLike) -> PhoneSession:
        """Publish the successor before closing the old transport outside the lock."""
        session = PhoneSession(user_id=user_id, websocket=websocket)
        async with self._lock:
            old = self._sessions.get(user_id)
            self._sessions[user_id] = session
        if old:
            try:
                await asyncio.wait_for(
                    old.websocket.close(1000, "Replaced by new connection"), timeout=2.0
                )
            except Exception:
                log.debug("Could not close replaced session", exc_info=True)
        log.info("Phone connected: user=%s", user_id)
        return session

    async def unregister(self, user_id: str, session: PhoneSession) -> bool:
        """Only the exact owner may remove a connection and its governor state."""
        async with self._lock:
            if self._sessions.get(user_id) is not session:
                return False
            del self._sessions[user_id]
            log.info("Phone disconnected: user=%s", user_id)
            return True

    async def is_current(self, user_id: str, session: PhoneSession) -> bool:
        async with self._lock:
            return self._sessions.get(user_id) is session

    async def get_session(self, user_id: str) -> Optional[PhoneSession]:
        """Get the active phone session for a user."""
        async with self._lock:
            return self._sessions.get(user_id)

    async def send_to_user(
        self, user_id: str, command: dict, wait_ack: bool = True
    ) -> CommandAck:
        """Route a command to a user's phone. Returns ack."""
        session = await self.get_session(user_id)
        if not session:
            return CommandAck(
                success=False,
                message="No phone connected. Open Intiface and connect to the relay.",
            )

        if wait_ack:
            return await session.send_command(command)
        else:
            await session.send_fire_and_forget(command)
            return CommandAck(success=True, message="Sent (no ack requested)")

    async def update_heartbeat(self, user_id: str, owner: PhoneSession | None = None):
        """Mark that a heartbeat pong was received."""
        async with self._lock:
            session = self._sessions.get(user_id)
            if session and (owner is None or session is owner):
                session.last_heartbeat = time.time()

    async def update_devices(self, user_id: str, devices: list[dict], owner: PhoneSession | None = None):
        """Update the device list for a user's session."""
        async with self._lock:
            session = self._sessions.get(user_id)
            if session and (owner is None or session is owner):
                session.devices = devices

    async def get_devices(self, user_id: str) -> list[dict]:
        """Get device list for a user."""
        session = await self.get_session(user_id)
        return session.devices if session else []

    async def get_all_sessions(self) -> dict[str, PhoneSession]:
        """Get snapshot of all sessions (for heartbeat monitor)."""
        async with self._lock:
            return dict(self._sessions)

    async def get_sole_user_id(self) -> Optional[str]:
        """If exactly one phone session is active, return its user_id.
        Used for authless MCP access (e.g. claude.ai connector)."""
        async with self._lock:
            if len(self._sessions) == 1:
                return next(iter(self._sessions))
            return None

    @property
    def active_count(self) -> int:
        return len(self._sessions)


# Singleton
registry = SessionRegistry()
