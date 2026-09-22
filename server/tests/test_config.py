"""Configuration contract: defaults, env mapping, and refusal to start.

`app.config` is the single source of truth (architecture doc §3). These tests
pin the mapping from `SB_*` variables to fields — including the one variable
whose name does not match its field — and the validation that makes an
invalid deployment fail loudly instead of silently falling back.
"""
import os

import pytest
from pydantic import ValidationError

from app.config import DEFAULT_DB_PATH, DEFAULT_PATTERNS_DIR, Settings, settings, validate


@pytest.fixture()
def clean_env(monkeypatch):
    """Every test starts from an environment with no SB_* variables at all."""
    for key in [k for k in os.environ if k.startswith("SB_")]:
        monkeypatch.delenv(key, raising=False)
    return monkeypatch


def fresh() -> Settings:
    """Settings built from the process environment only (never from .env)."""
    return Settings(_env_file=None)


def test_defaults_match_the_pre_m2a_values(clean_env):
    default = fresh()
    assert default.HOST == "0.0.0.0"
    assert default.PORT == 8420
    assert default.SECRET_KEY == ""
    assert default.SESSION_TOKEN_TTL_HOURS == 24
    assert default.REGISTRATION_OPEN is True  # flipped to False in M2b (D5)
    assert default.RATE_LIMIT_AUTH == "5/minute"
    assert default.RATE_LIMIT_COMMANDS == "120/minute"
    assert default.RATE_LIMIT_GLOBAL == "300/minute"
    assert default.MAX_WS_PER_IP == 3
    assert default.BAN_THRESHOLD == 20
    assert default.BAN_DURATION_MINUTES == 30
    assert default.HEARTBEAT_INTERVAL == 2.0
    assert default.HEARTBEAT_TIMEOUT == 6.0
    assert default.GOVERNOR_ENABLED is True
    assert default.GOVERNOR_HEAT_RATE == 3.0
    assert default.GOVERNOR_COOL_RATE == 2.0
    assert default.GOVERNOR_COOLDOWN_THRESHOLD == 90.0
    assert default.GOVERNOR_COOLDOWN_EXIT == 30.0
    assert default.GOVERNOR_COOLDOWN_DURATION == 30.0
    assert default.DB_PATH == DEFAULT_DB_PATH
    assert default.PATTERNS_DIR == DEFAULT_PATTERNS_DIR


def test_governor_cooldown_enter_keeps_its_legacy_variable_name(clean_env):
    """SB_GOVERNOR_COOLDOWN_ENTER → GOVERNOR_COOLDOWN_THRESHOLD."""
    clean_env.setenv("SB_GOVERNOR_COOLDOWN_ENTER", "77")
    assert fresh().GOVERNOR_COOLDOWN_THRESHOLD == 77.0


def test_cors_origins_split_like_the_legacy_module(clean_env):
    # RFC 5737 documentation addresses: the repo's leak scanner forbids
    # placeholder-looking hostnames in source, so use the reserved ranges.
    clean_env.setenv("SB_CORS_ORIGINS", "https://198.51.100.10, https://203.0.113.7 ,")
    assert fresh().cors_origin_list() == ["https://198.51.100.10", "https://203.0.113.7"]
    clean_env.setenv("SB_CORS_ORIGINS", "*")
    assert fresh().cors_origin_list() == ["*"]


def test_env_values_are_coerced(clean_env):
    clean_env.setenv("SB_PORT", "9000")
    clean_env.setenv("SB_REGISTRATION_OPEN", "false")
    clean_env.setenv("SB_HEARTBEAT_INTERVAL", "0.5")
    clean_env.setenv("SB_DB_PATH", "/tmp/ai-intou/custom.db")
    cfg = fresh()
    assert cfg.PORT == 9000
    assert cfg.REGISTRATION_OPEN is False
    assert cfg.HEARTBEAT_INTERVAL == 0.5
    assert cfg.DB_PATH == "/tmp/ai-intou/custom.db"


@pytest.mark.parametrize("name,value", [
    ("SB_PORT", "not-a-number"),
    ("SB_REGISTRATION_OPEN", "maybe"),
    ("SB_HEARTBEAT_TIMEOUT", ""),
])
def test_invalid_values_refuse_to_load(clean_env, name, value):
    """Documented M2a strictness change: no silent fallback to a default."""
    clean_env.setenv(name, value)
    with pytest.raises(ValidationError):
        fresh()


def test_unknown_sb_variables_are_ignored(clean_env):
    clean_env.setenv("SB_SOMETHING_FROM_THE_FUTURE", "1")
    assert fresh().HOST  # does not raise


def test_validate_reports_missing_secret_key():
    with pytest.raises(RuntimeError, match="SB_SECRET_KEY is not set"):
        validate(Settings(_env_file=None, SECRET_KEY=""))


def test_validate_reports_short_static_token():
    with pytest.raises(RuntimeError, match="at least 32 characters"):
        validate(Settings(_env_file=None, SECRET_KEY="x" * 40, STATIC_BEARER_TOKEN="too-short"))


def test_validate_reports_bad_port():
    with pytest.raises(RuntimeError, match="SB_PORT"):
        validate(Settings(_env_file=None, SECRET_KEY="x" * 40, PORT=0))


def test_validate_accepts_a_healthy_config():
    validate(Settings(_env_file=None, SECRET_KEY="x" * 64))
    validate(settings)  # the pytest environment is itself valid


def test_legacy_alias_module_reads_the_same_values():
    """`server.config` must never become a second source of truth."""
    from server import config as legacy

    assert legacy.SECRET_KEY == settings.SECRET_KEY
    assert legacy.DB_PATH == settings.DB_PATH
    assert legacy.PATTERNS_DIR == settings.PATTERNS_DIR
    assert legacy.CORS_ORIGINS == settings.cors_origin_list()
    assert legacy.HEARTBEAT_INTERVAL_S == settings.HEARTBEAT_INTERVAL
    assert legacy.HEARTBEAT_TIMEOUT_S == settings.HEARTBEAT_TIMEOUT
    assert legacy.GOVERNOR_COOLDOWN_THRESHOLD == settings.GOVERNOR_COOLDOWN_THRESHOLD
    assert legacy.MAX_WS_PER_IP == settings.MAX_WS_PER_IP
