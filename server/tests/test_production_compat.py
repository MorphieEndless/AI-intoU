"""Deployed behavior preserved without deployed configuration or data."""
import asyncio

import pytest
from app.config import Settings
from server import auth, config, mcp_tools
from server.models import PatternCommand
from server.pattern_store import PatternStore


def test_static_token_account_binding(monkeypatch):
    monkeypatch.setattr(config, "STATIC_USER_ID", "existing-account")
    assert auth.verify_token(config.STATIC_BEARER_TOKEN)["user_id"] == "existing-account"
    monkeypatch.setattr(config, "STATIC_USER_ID", "")
    assert auth.verify_token(config.STATIC_BEARER_TOKEN)["user_id"] == "static-bearer-user"


def test_binding_uses_central_settings(monkeypatch):
    monkeypatch.setenv("SB_STATIC_USER_ID", "existing-account")
    assert Settings(_env_file=None).STATIC_USER_ID == "existing-account"


def test_repeat_60_and_short_patterns_remain_valid(tmp_path):
    store = PatternStore(str(tmp_path))
    pattern = store.create("user", "short", [{"duration_ms": 100}], repeat=60)
    assert pattern.total_ms() == 6000
    assert store.get("user", pattern.id).repeat == 60
    with pytest.raises(ValueError):
        store.create("user", "invalid", [{"duration_ms": 100}], repeat=61)
    with pytest.raises(ValueError):
        store.create("user", "long", [{"duration_ms": 10001}], repeat=60)


def test_escalate_defaults_to_ten_seconds_and_zero_is_explicit(monkeypatch):
    commands = []
    async def send(command, **kwargs):
        commands.append((command, kwargs))
        return "ok"
    async def quantize(device, output, value):
        return value
    monkeypatch.setattr(mcp_tools, "_send", send)
    monkeypatch.setattr(mcp_tools, "_quantize_for_device", quantize)
    handler = mcp_tools._make_pattern_handler("escalate")
    asyncio.run(handler(duration=5))
    asyncio.run(handler(duration=5, hold_seconds=0))
    assert commands[0][0]["hold_seconds"] == 10
    assert commands[0][1]["duration"] == 15
    assert commands[1][0]["hold_seconds"] == 0
    assert commands[1][1]["duration"] == 0
    assert PatternCommand(pattern="escalate").hold_seconds == 10
