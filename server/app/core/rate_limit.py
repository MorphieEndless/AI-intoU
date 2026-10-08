"""In-memory rate limiting and progressive IP banning (single process, §1.7).

Moved out of the legacy `server/auth.py` so the new access layer (`app/api`)
can use them without importing the legacy package. `server.auth` re-exports
the same singletons, so legacy callers keep working and every caller shares
one counter.
"""
from __future__ import annotations

import asyncio
import time
from collections import defaultdict

from app.config import settings


class IPBanTracker:
    """Track failed auth attempts per IP; ban temporarily past a threshold."""

    def __init__(self):
        self._failures: dict[str, list[float]] = defaultdict(list)
        self._bans: dict[str, float] = {}  # ip → ban_expires_at timestamp
        self._lock = asyncio.Lock()

    async def record_failure(self, ip: str):
        async with self._lock:
            now = time.time()
            window = now - 3600  # 1-hour sliding window
            self._failures[ip] = [t for t in self._failures[ip] if t > window]
            self._failures[ip].append(now)
            if len(self._failures[ip]) >= settings.BAN_THRESHOLD:
                self._bans[ip] = now + (settings.BAN_DURATION_MINUTES * 60)
                self._failures[ip] = []

    async def is_banned(self, ip: str) -> bool:
        async with self._lock:
            if ip not in self._bans:
                return False
            if time.time() > self._bans[ip]:
                del self._bans[ip]
                return False
            return True

    async def clear_failures(self, ip: str):
        async with self._lock:
            self._failures.pop(ip, None)

    @property
    def banned_count(self) -> int:
        now = time.time()
        return sum(1 for exp in self._bans.values() if exp > now)


class RateLimiter:
    """Sliding-window counters. Rate strings look like "5/minute"."""

    PERIODS = {"second": 1, "minute": 60, "hour": 3600, "day": 86400}

    def __init__(self):
        self._windows: dict[str, list[float]] = defaultdict(list)
        self._lock = asyncio.Lock()

    @staticmethod
    def _parse_rate(rate_str: str) -> tuple[int, int]:
        count_str, period_str = rate_str.split("/")
        return int(count_str), RateLimiter.PERIODS[period_str]

    async def check(self, key: str, rate_str: str) -> bool:
        """True if allowed (and the attempt is recorded)."""
        max_count, period = self._parse_rate(rate_str)
        async with self._lock:
            now = time.time()
            window_start = now - period
            self._windows[key] = [t for t in self._windows[key] if t > window_start]
            if len(self._windows[key]) >= max_count:
                return False
            self._windows[key].append(now)
            return True


ip_tracker = IPBanTracker()
rate_limiter = RateLimiter()
