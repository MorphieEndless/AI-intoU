"""Approved library behavior; isolated database and no hardware transport."""
import asyncio
import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from server import pattern_routes, mcp_tools
from conftest import phone_token_for
from server.pattern_store import PatternStore


@pytest.fixture
def library(tmp_path, monkeypatch):
    store = PatternStore(str(tmp_path / "legacy"), db_path=str(tmp_path / "library.db"))
    monkeypatch.setattr(pattern_routes, "pattern_store", store)
    monkeypatch.setattr(mcp_tools, "pattern_store", store)
    app = FastAPI()
    app.include_router(pattern_routes.router)
    with TestClient(app) as client:
        yield store, client, {"Authorization": "Bearer " + phone_token_for("alice")}


def test_builtin_is_unique_and_personal(library):
    store, client, headers = library
    page = client.get("/patterns?include_steps=true", headers=headers).json()
    assert page["total"] == 1
    p = page["patterns"][0]
    assert p["id"] == "builtin-wave" and p["name"] == "示例波形-波浪"
    assert p["total_ms"] == 30000 and p["steps"][0]["vibrate"] == .2
    assert client.delete("/patterns/builtin-wave", headers=headers).status_code == 409
    assert client.patch("/patterns/builtin-wave", headers=headers, json={"is_liked": True, "description": "我的备注"}).status_code == 200
    assert store.get("alice", "builtin-wave").is_liked
    assert not store.get("bob", "builtin-wave").is_liked
    assert store.get("bob", "builtin-wave").description != "我的备注"
    assert client.get("/patterns", headers=headers).json()["total"] == 1


def test_patch_is_partial_strict_and_id_scoped(library):
    store, client, headers = library
    p = store.create("alice", "节奏", [{"duration_ms": 500, "vibrate": .2}])
    before = p.model_dump()
    assert client.patch(f"/patterns/{p.id}", headers=headers, json={"is_liked": True}).status_code == 200
    assert client.patch(f"/patterns/{p.id}", headers=headers, json={"is_favorite": True}).status_code == 200
    assert client.patch(f"/patterns/{p.id}", headers=headers, json={"description": "  留白  "}).json()["description"] == "留白"
    after = store.get("alice", p.id)
    assert after.is_liked and after.is_favorite
    assert after.steps == p.steps and after.repeat == before["repeat"]
    for body in [{"is_liked": "true"}, {"is_liked": None}, {"steps": []}, {"user_id": "bob"}, {"description": "x" * 121}, {}]:
        assert client.patch(f"/patterns/{p.id}", headers=headers, json=body).status_code == 422
    other = {"Authorization": "Bearer " + phone_token_for("bob")}
    for method in ["get", "patch", "delete"]:
        kwargs = {"json": {"is_liked": False}} if method == "patch" else {}
        assert getattr(client, method)(f"/patterns/{p.id}", headers=other, **kwargs).status_code == 404
    assert client.patch(f"/patterns/{p.id}", json={"is_liked": True}).status_code == 401


def test_filters_precede_pagination_and_search_notes(library):
    store, client, headers = library
    for i in range(4):
        p = store.create("alice", f"节奏{i}", [{"duration_ms": 100}])
        store.update_metadata("alice", p.id, {"is_favorite": i % 2 == 0, "is_liked": i == 1, "description": "呼吸"})
    assert client.get("/patterns?filter=favorites&limit=1&offset=1", headers=headers).json()["total"] == 2
    assert len(client.get("/patterns?filter=liked", headers=headers).json()["patterns"]) == 1
    assert client.get("/patterns?q=呼吸", headers=headers).json()["total"] == 4
    assert client.get("/patterns?filter=bad", headers=headers).status_code == 422


def test_delete_and_undo_are_private_and_do_not_control(library, monkeypatch):
    store, client, headers = library
    p = store.create("alice", "可撤销", [{"duration_ms": 100}])
    async def forbidden(*args, **kwargs):
        pytest.fail("metadata triggered a hardware command")
    monkeypatch.setattr(mcp_tools.registry, "send_to_user", forbidden)
    assert client.delete(f"/patterns/{p.id}", headers=headers).status_code == 200
    assert store.get("alice", p.id) is None
    other = {"Authorization": "Bearer " + phone_token_for("bob")}
    assert client.post(f"/patterns/{p.id}/restore", headers=other).status_code == 404
    assert client.post(f"/patterns/{p.id}/restore", headers=headers).status_code == 200
    assert store.get("alice", p.id)
    client.delete(f"/patterns/{p.id}", headers=headers)
    monkeypatch.setattr("app.domain.patterns.time.time", lambda: 10**12)
    assert client.post(f"/patterns/{p.id}/restore", headers=headers).status_code == 409


def test_legacy_import_once_preserves_fields_and_tombstone(tmp_path):
    root = tmp_path / "legacy"
    root.mkdir()
    original = [{"id": "abc123456789", "name": "旧波形", "description": "保留", "device": "yingti", "repeat": 2,
                 "intensity_scale": .4, "steps": [{"duration_ms": 100, "vibrate": .5}], "created_at": 123., "updated_at": 456.}]
    source = root / "alice.json"
    source.write_text(json.dumps(original))
    db = str(tmp_path / "live.db")
    store = PatternStore(str(root), db_path=db)
    p = store.get("alice", original[0]["id"])
    assert p.repeat == 2 and p.intensity_scale == .4 and p.description == "保留"
    assert p.created_at == 123. and not p.is_liked and not p.is_favorite
    store.delete("alice", p.id)
    restarted = PatternStore(str(root), db_path=db)
    assert restarted.get("alice", p.id) is None
    assert json.loads(source.read_text()) == original
    assert not list(root.glob("*.tmp"))


def test_corrupt_import_fails_without_losing_other_data(tmp_path):
    root = tmp_path / "legacy"
    root.mkdir()
    (root / "alice.json").write_text("[{broken]")
    with pytest.raises(ValueError, match="legacy"):
        PatternStore(str(root), db_path=str(tmp_path / "live.db"))


def test_mcp_preference_view_is_read_only_and_scoped(library):
    store, _, _ = library
    p = store.create("alice", "AI波形", [{"duration_ms": 100}], description="可选备注")
    store.update_metadata("alice", p.id, {"is_liked": True})
    token = mcp_tools.current_user_id.set("alice")
    try:
        view = json.loads(asyncio.run(mcp_tools.get_pattern_preferences()))
        saved = next(x for x in view["patterns"] if x["id"] == p.id)
        assert saved["is_liked"] and not saved["is_favorite"]
        assert saved["description"] == "可选备注"
        assert "is_liked" not in next(t for t in mcp_tools.TOOLS if t["name"] == "create_pattern")["inputSchema"]["properties"]
    finally:
        mcp_tools.current_user_id.reset(token)


def test_same_legacy_id_for_different_users_is_safe(tmp_path):
    root = tmp_path / "legacy"
    root.mkdir()
    for user in ["alice", "bob"]:
        (root / f"{user}.json").write_text(json.dumps([{"id": "abc123456789", "name": user, "steps": [{"duration_ms": 100}]}]))
    store = PatternStore(str(root), db_path=str(tmp_path / "live.db"))
    store.update_metadata("alice", "abc123456789", {"is_liked": True})
    assert store.get("alice", "abc123456789").is_liked
    assert not store.get("bob", "abc123456789").is_liked
