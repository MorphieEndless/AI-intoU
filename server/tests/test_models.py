"""ORM model contract: schema shape, constraints, relationships.

Implements the data model of docs/redesign/02-architecture.md §4.
"""
import json
import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from app.models import (
    ApiToken, Base, Device, Pattern, SafetyConfig, User,
)


@pytest.fixture()
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with factory() as s:
        yield s


def make_user(username="morphie"):
    return User(id=str(uuid.uuid4()), username=username, password_hash="x")


def test_user_roundtrip(session):
    u = make_user()
    session.add(u)
    session.commit()
    got = session.get(User, u.id)
    assert got.username == "morphie"
    assert got.is_admin == 0 and got.is_active == 1
    assert got.created_at  # default timestamp applied


def test_username_unique(session):
    session.add(make_user("alice"))
    session.commit()
    session.add(make_user("alice"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_api_token_roundtrip(session):
    u = make_user()
    t = ApiToken(
        id=str(uuid.uuid4()), user_id=u.id, name="claude-desktop",
        kind="agent", token_hash="deadbeef", prefix="aiu_agent_12",
    )
    u.tokens.append(t)
    session.add(u)
    session.commit()
    got = session.get(ApiToken, t.id)
    assert got.user.username == "morphie"
    assert got.scope_list == ["control", "status", "config"]
    assert got.is_active


def test_api_token_kind_constraint(session):
    u = make_user()
    session.add(u)
    session.commit()
    session.add(ApiToken(
        id=str(uuid.uuid4()), user_id=u.id, name="bad",
        kind="alien", token_hash="x", prefix="p",
    ))
    with pytest.raises(IntegrityError):
        session.commit()


def test_token_active_states(session):
    u = make_user()
    session.add(u)
    session.commit()
    revoked = ApiToken(id="t1", user_id=u.id, name="a", kind="agent",
                       token_hash="h1", prefix="p", revoked_at="2026-01-01T00:00:00+00:00")
    expired = ApiToken(id="t2", user_id=u.id, name="b", kind="phone",
                       token_hash="h2", prefix="p", expires_at="2000-01-01T00:00:00+00:00")
    ok = ApiToken(id="t3", user_id=u.id, name="c", kind="human",
                  token_hash="h3", prefix="p")
    session.add_all([revoked, expired, ok])
    session.commit()
    assert not revoked.is_active
    assert not expired.is_active
    assert ok.is_active


def test_full_object_graph_and_cascade(session):
    u = make_user()
    u.tokens.append(ApiToken(id="t", user_id=u.id, name="rikka", kind="agent",
                             token_hash="h", prefix="p"))
    u.devices.append(Device(id="d", user_id=u.id, name="bedside phone",
                            platform="android"))
    u.patterns.append(Pattern(id="p1", user_id=u.id, name="tease",
                              definition=json.dumps({"steps": []})))
    session.add(u)
    session.add(SafetyConfig(user_id=u.id, governor_enabled=0, heat_rate=2.5))
    session.commit()

    got = session.get(User, u.id)
    assert len(got.tokens) == 1 and len(got.devices) == 1 and len(got.patterns) == 1
    cfg = session.get(SafetyConfig, u.id)
    assert cfg.governor_enabled == 0 and cfg.heat_rate == 2.5
    assert cfg.cool_rate is None  # NULL = follow server default

    session.delete(u)
    session.commit()
    assert session.get(ApiToken, "t") is None
    assert session.get(Device, "d") is None
    assert session.get(Pattern, "p1") is None
