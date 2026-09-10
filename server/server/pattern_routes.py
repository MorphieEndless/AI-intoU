"""Authenticated app access to the same per-user library used by MCP.

No sole-phone/session fallback. Reading or deleting never starts a device.
Replay is explicitly confirmed in the app and executed by its dispatcher.
"""
import re
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from .auth import extract_token, verify_token, rate_limiter
from .pattern_store import pattern_store

router = APIRouter(prefix="/patterns", tags=["patterns"])

async def user_id(request: Request) -> str:
    token = extract_token(request.headers.get("authorization", ""))
    user = verify_token(token) if token else None
    if not user:
        raise HTTPException(401, "Valid Bearer token required")
    if not await rate_limiter.check(f"library:{user['user_id']}", "60/minute"):
        raise HTTPException(429, "Too many library requests")
    return user["user_id"]

@router.get("")
async def list_patterns(request: Request, offset: int = 0, limit: int = 30):
    uid = await user_id(request)
    if offset < 0 or not 1 <= limit <= 50:
        raise HTTPException(400, "Invalid pagination")
    patterns = sorted(pattern_store.list(uid), key=lambda p: p["created_at"], reverse=True)
    result = []
    for p in patterns[offset:offset + limit]:
        result.append({k: v for k, v in p.items() if k != "steps"} | {
            "step_count": len(p["steps"]),
            "total_ms": sum(s["duration_ms"] for s in p["steps"]) * p["repeat"],
        })
    return JSONResponse({"patterns": result, "total": len(patterns)}, headers={"Cache-Control": "no-store"})

async def find(request: Request, pattern_id: str):
    uid = await user_id(request)
    if not re.fullmatch(r"[0-9a-f]{12}", pattern_id):
        raise HTTPException(404, "Pattern not found")
    # Exact IDs only: a user may give another waveform an ID-shaped name.
    pattern = next((p for p in pattern_store._load(uid) if p.id == pattern_id), None)
    if pattern is None:
        raise HTTPException(404, "Pattern not found")
    return uid, pattern

@router.get("/{pattern_id}")
async def get_pattern(request: Request, pattern_id: str):
    _, pattern = await find(request, pattern_id)
    return JSONResponse(pattern.model_dump(), headers={"Cache-Control": "no-store"})

@router.delete("/{pattern_id}")
async def delete_pattern(request: Request, pattern_id: str):
    uid, _ = await find(request, pattern_id)
    # No awaits between load/save; same event-loop serialization as MCP CRUD.
    patterns = pattern_store._load(uid)
    pattern_store._save(uid, [p for p in patterns if p.id != pattern_id])
    return JSONResponse({"deleted": pattern_id}, headers={"Cache-Control": "no-store"})
