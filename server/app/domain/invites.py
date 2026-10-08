"""Invite codes — how a new person gets an account on a closed instance.

Registration is closed by default (D5). The owner mints a short code
(CLI or the in-app admin page), sends it privately, and the newcomer types
it into the App's registration form. Redeeming the code and creating the
account happen in one transaction: a failed registration never burns a use,
and a single-use code cannot be redeemed twice even by concurrent requests.

Code format: 12 Crockford base32 characters shown as `XXXX-XXXX-XXXX`
(60 bits). Input is forgiving — case, spaces, dashes and the look-alikes
O/I/L are normalised — because people copy these out of chat apps.
Only a SHA-256 digest and the first group are stored.

Layering: no FastAPI, no request objects (architecture doc §1.4).
"""
from __future__ import annotations

import hashlib
import secrets
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import case, or_, select, update

from app.domain.identity import IdentityError, create_user
from app.models import Invite, User
from app.models.base import utcnow_iso

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # Crockford: no I L O U
CODE_LENGTH = 12
GROUP = 4
MAX_USES_LIMIT = 50
MAX_EXPIRY_DAYS = 90
DEFAULT_EXPIRY_DAYS = 7
MAX_NOTE = 80
_LOOKALIKES = str.maketrans({"O": "0", "I": "1", "L": "1"})


class InviteError(IdentityError):
    """User-facing invite problem; the message is safe to show as-is."""


INVALID_CODE_MESSAGE = "邀请码无效、已过期或已用完，请向邀请你的人重新要一个"


def normalize_code(raw: str | None) -> str | None:
    """Canonical 12-character form, or None if it cannot be a valid code."""
    if not raw:
        return None
    cleaned = "".join(ch for ch in raw.upper() if not ch.isspace() and ch != "-")
    cleaned = cleaned.translate(_LOOKALIKES)
    if len(cleaned) != CODE_LENGTH or any(ch not in ALPHABET for ch in cleaned):
        return None
    return cleaned


def format_code(canonical: str) -> str:
    return "-".join(canonical[i:i + GROUP] for i in range(0, len(canonical), GROUP))


def hash_code(canonical: str) -> str:
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def generate_code() -> str:
    """A fresh canonical code (not formatted)."""
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def create_invite(
    session,
    created_by: User | None,
    *,
    max_uses: int = 1,
    expires_in_days: int | None = DEFAULT_EXPIRY_DAYS,
    note: str = "",
) -> tuple[str, Invite]:
    """Mint an invite. Returns (formatted plaintext code, row) — code once."""
    if not 1 <= int(max_uses) <= MAX_USES_LIMIT:
        raise InviteError(f"可用次数需在 1-{MAX_USES_LIMIT} 之间")
    if expires_in_days is not None and not 1 <= int(expires_in_days) <= MAX_EXPIRY_DAYS:
        raise InviteError(f"有效期需在 1-{MAX_EXPIRY_DAYS} 天之间")
    note = (note or "").strip()
    if len(note) > MAX_NOTE:
        raise InviteError(f"备注最多 {MAX_NOTE} 个字符")

    canonical = generate_code()
    expires_at = None
    if expires_in_days is not None:
        expires_at = (datetime.now(timezone.utc) + timedelta(days=int(expires_in_days))).isoformat()
    row = Invite(
        id=str(uuid.uuid4()),
        code_hash=hash_code(canonical),
        prefix=canonical[:GROUP],
        note=note,
        created_by=created_by.id if created_by is not None else None,
        max_uses=int(max_uses),
        used_count=0,
        expires_at=expires_at,
        used_by="",
        created_at=utcnow_iso(),
    )
    session.add(row)
    session.flush()
    return format_code(canonical), row


def list_invites(session) -> list[Invite]:
    return list(session.scalars(select(Invite).order_by(Invite.created_at.desc())))


def find_invite(session, identifier: str) -> Invite:
    """By full id or by the 4-character display prefix."""
    identifier = (identifier or "").strip()
    if not identifier:
        raise InviteError("需要邀请码 id 或前缀")
    matches = list(session.scalars(
        select(Invite).where(or_(Invite.id == identifier, Invite.prefix == identifier.upper()))
    ))
    if not matches:
        raise InviteError(f"找不到邀请码 {identifier!r}")
    if len(matches) > 1:
        raise InviteError(f"前缀 {identifier!r} 对应 {len(matches)} 个邀请码，请用完整 id")
    return matches[0]


def revoke_invite(session, identifier: str) -> tuple[Invite, bool]:
    row = find_invite(session, identifier)
    if row.revoked_at is not None:
        return row, False
    row.revoked_at = utcnow_iso()
    session.flush()
    return row, True


def peek_invite(session, raw_code: str | None) -> Invite | None:
    """The redeemable invite behind a code, or None. Read-only."""
    canonical = normalize_code(raw_code)
    if canonical is None:
        return None
    row = session.scalar(select(Invite).where(Invite.code_hash == hash_code(canonical)))
    if row is None or not row.is_redeemable:
        return None
    return row


def register_with_invite(session, raw_code: str | None, username: str, password: str) -> User:
    """Redeem one use of an invite and create a normal (non-owner) account.

    Runs inside the caller's transaction: if account creation fails
    (taken username, weak password) the whole thing rolls back and the
    invite keeps its use.
    """
    row = peek_invite(session, raw_code)
    if row is None:
        raise InviteError(INVALID_CODE_MESSAGE)

    user = create_user(session, username, password, is_admin=False)

    # Conditional increment: the WHERE clause is the real guard against two
    # concurrent requests both redeeming the last use.
    # `used_by` is appended in SQL too, so concurrent redemptions of a
    # multi-use code cannot overwrite each other's names.
    now = utcnow_iso()
    used_by = case(
        (Invite.used_by == "", user.username),
        else_=Invite.used_by + "," + user.username,
    )
    result = session.execute(
        update(Invite)
        .where(
            Invite.id == row.id,
            Invite.used_count < Invite.max_uses,
            Invite.revoked_at.is_(None),
        )
        .values(used_count=Invite.used_count + 1, last_used_at=now, used_by=used_by)
        .execution_options(synchronize_session=False)
    )
    if result.rowcount != 1:
        raise InviteError(INVALID_CODE_MESSAGE)
    session.refresh(row)
    return user
