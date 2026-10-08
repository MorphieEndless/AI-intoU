"""Offline app-library API checks; no real phone or deployment is contacted."""
import os
import tempfile
import unittest
from pathlib import Path

_tmp = tempfile.TemporaryDirectory()
os.environ["SB_DB_PATH"] = str(Path(_tmp.name) / "test.db")
os.environ["SB_PATTERNS_DIR"] = str(Path(_tmp.name) / "patterns")
os.environ["SB_SECRET_KEY"] = "offline-pattern-route-verification-key-123456"

from fastapi import FastAPI
from fastapi.testclient import TestClient
from server import pattern_routes, mcp_tools
from server.pattern_store import PatternStore
from app.db import session_scope, upgrade_to_head
from app.domain.identity import hash_password, mint_token
from app.models import User


def phone_token(user_id):
    """A phone token for an account with a fixed id (the App's credential)."""
    upgrade_to_head()
    with session_scope() as s:
        user = s.get(User, user_id)
        if user is None:
            user = User(id=user_id, username=user_id, password_hash=hash_password("password-123"))
            s.add(user)
            s.flush()
        return mint_token(s, user, name="test", kind="phone")[0]


def agent_token(user_id):
    phone_token(user_id)
    with session_scope() as s:
        return mint_token(s, s.get(User, user_id), name="ai", kind="agent")[0]

app = FastAPI()
app.include_router(pattern_routes.router)
client = TestClient(app)

class PatternRoutesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = PatternStore(self.temp.name, db_path=str(Path(self.temp.name) / "test.db"))
        pattern_routes.pattern_store = self.store
        mcp_tools.pattern_store = self.store
        self.token = phone_token("test-user")
        self.headers = {"Authorization": "Bearer " + self.token}
        self.pattern = self.store.create("test-user", "样例", [{"duration_ms": 100, "vibrate": 0.5}], intensity_scale=0.4)
    def tearDown(self):
        self.temp.cleanup()
    def test_auth_required_for_all_routes(self):
        for method, url in [("get", "/patterns"), ("get", "/patterns/" + self.pattern.id), ("delete", "/patterns/" + self.pattern.id)]:
            self.assertEqual(getattr(client, method)(url).status_code, 401)
    def test_other_user_cannot_read_or_delete(self):
        headers = {"Authorization": "Bearer " + phone_token("other-user")}
        self.assertEqual(client.get("/patterns", headers=headers).json()["patterns"][0]["id"], "builtin-wave")
        for method in ["get", "delete"]:
            self.assertEqual(getattr(client, method)("/patterns/" + self.pattern.id, headers=headers).status_code, 404)
        self.assertIsNotNone(self.store.get("test-user", self.pattern.id))
    def test_list_detail_delete_share_mcp_store(self):
        import asyncio
        mcp_tools.current_user_id.set("test-user")
        asyncio.run(mcp_tools.create_pattern("AI新建", [{"duration_ms": 200, "constrict": 0.5}]))
        data = client.get("/patterns", headers=self.headers).json()
        self.assertEqual(data["total"], 3)
        self.assertNotIn("steps", data["patterns"][0])
        detail = client.get("/patterns/" + self.pattern.id, headers=self.headers).json()
        self.assertEqual(detail["intensity_scale"], 0.4)
        self.assertEqual(detail["steps"][0]["vibrate"], 0.5)
        self.assertEqual(client.delete("/patterns/" + self.pattern.id, headers=self.headers).status_code, 200)
        self.assertIsNone(self.store.get("test-user", self.pattern.id))
    def test_deletion_by_id_does_not_delete_id_shaped_name(self):
        other = self.store.create("test-user", self.pattern.id, [{"duration_ms": 100}])
        client.delete("/patterns/" + self.pattern.id, headers=self.headers)
        self.assertIsNotNone(self.store.get("test-user", other.id))
    def test_pagination_and_invalid_id(self):
        self.assertEqual(client.get("/patterns?offset=2&limit=1", headers=self.headers).json()["patterns"], [])
        self.assertEqual(client.get("/patterns?limit=51", headers=self.headers).status_code, 400)
        self.assertEqual(client.get("/patterns?offset=-1", headers=self.headers).status_code, 400)
        self.assertEqual(client.get("/patterns/not-an-id", headers=self.headers).status_code, 404)
    def test_agent_token_has_no_rest_library_access(self):
        # AI clients use the MCP tools; the REST library is the App's.
        headers = {"Authorization": "Bearer " + agent_token("test-user")}
        self.assertEqual(client.get("/patterns", headers=headers).status_code, 403)

if __name__ == "__main__":
    unittest.main(verbosity=2)
