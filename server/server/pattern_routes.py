"""Authenticated, exact-ID library API. No metadata route controls hardware."""
import re
from typing import Literal
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from app.schemas.patterns import (
    BUILTIN_ID, MetadataPatch, PatternPage, PatternSummary, StoredPattern, DeleteResult,
)
from .auth import extract_token, verify_token, rate_limiter
from .pattern_store import pattern_store

router = APIRouter(prefix="/patterns", tags=["patterns"])


async def user_id(request: Request) -> str:
    token = extract_token(request.headers.get("authorization", ""))
    user = verify_token(token) if token else None
    if not user:
        raise HTTPException(401, "Valid Bearer token required")
    if not await rate_limiter.check(f"library:{user['user_id']}", "120/minute"):
        raise HTTPException(429, "Too many library requests")
    return user["user_id"]


def response(model):
    return JSONResponse(model.model_dump(exclude_none=True), headers={"Cache-Control": "no-store"})


@router.get("", response_model=PatternPage)
async def list_patterns(request: Request, offset: int = 0, limit: int = 30,
    filter: Literal["all", "liked", "favorites"] = "all", q: str = "", include_steps: bool = False):
    uid = await user_id(request)
    if offset < 0 or not 1 <= limit <= 50 or len(q) > 120:
        raise HTTPException(400, "Invalid pagination or search")
    query = q.strip().casefold()
    patterns = [p for p in pattern_store.library(uid)
        if (filter != "liked" or p.is_liked) and (filter != "favorites" or p.is_favorite)
        and (not query or query in (p.name + " " + p.description).casefold())]
    result = []
    for p in patterns[offset:offset + limit]:
        values = p.model_dump(exclude={"steps"})
        result.append(PatternSummary(**values, step_count=len(p.steps), total_ms=p.total_ms(),
            steps=p.steps if include_steps else None))
    return response(PatternPage(patterns=result, total=len(patterns)))


def validate_id(pattern_id):
    if pattern_id != BUILTIN_ID and not re.fullmatch(r"[0-9a-f]{12}", pattern_id):
        raise HTTPException(404, "Pattern not found")


async def find(request, pattern_id):
    uid = await user_id(request)
    validate_id(pattern_id)
    p = pattern_store.get_by_id(uid, pattern_id)
    if p is None:
        raise HTTPException(404, "Pattern not found")
    return uid, p


@router.get("/{pattern_id}", response_model=StoredPattern)
async def get_pattern(request: Request, pattern_id: str):
    _, p = await find(request, pattern_id)
    return response(p)


@router.patch("/{pattern_id}", response_model=StoredPattern)
async def patch_pattern(request: Request, pattern_id: str, patch: MetadataPatch):
    uid, _ = await find(request, pattern_id)
    p = pattern_store.update_metadata(uid, pattern_id, patch.model_dump(exclude_unset=True))
    if p is None:
        raise HTTPException(404, "Pattern not found")
    return response(p)


@router.delete("/{pattern_id}", response_model=DeleteResult)
async def delete_pattern(request: Request, pattern_id: str):
    uid, _ = await find(request, pattern_id)
    try:
        if not pattern_store.delete_id(uid, pattern_id):
            raise HTTPException(404, "Pattern not found")
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc
    return response(DeleteResult(deleted=pattern_id))


@router.post("/{pattern_id}/restore", response_model=StoredPattern)
async def restore_pattern(request: Request, pattern_id: str):
    uid = await user_id(request)
    validate_id(pattern_id)
    try:
        p = pattern_store.restore(uid, pattern_id)
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc
    if p is None:
        raise HTTPException(404, "Pattern not found")
    return response(p)
