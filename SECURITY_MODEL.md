# AI-intoU threat model

Repository: `MorphieEndless/AI-intoU` (no SECURITY_MODEL.md; drafted from code and `docs/redesign/02-architecture.md`, `AGENTS.md`). Facts marked **[user]** were confirmed by the repository owner; everything else is inferred from the code.

## System overview and trust boundaries

Self-hosted bridge: AI agent (any MCP client) → **AI-intoU server** (FastAPI + SQLite, Docker) → **樱趣 Android app** over WSS → BLE toy (SVAKOM SX589B). The server listens on loopback port 8420 by default (`server/docker-compose.yml`, `SB_BIND_ADDRESS` can widen it); public access is meant to go through an HTTPS reverse proxy (Caddy, `docs/DEPLOY.md`). Multi-user: one owner (admin) plus invited users, each an isolated tenant with their own phone, tokens, patterns and safety config.

Trust boundaries:
1. Internet → reverse proxy → server (`/mcp`, `/ws/phone`, `/auth/*`, `/api/*`, `/admin/*`, `/safety/*`, pattern routes, `/health`).
2. Tenant ↔ tenant inside the server (per-`user_id` isolation in session registry, DB rows, governor state).
3. AI agent ↔ physical device: the agent's tool calls become hardware output; the governor / dead-man switch sit on this boundary.
4. Server ↔ phone (WSS relay) and phone ↔ toy (BLE).
5. Phone ↔ update sources (GitHub + third-party mirror proxies) for APK self-update.

## Components

| Component | Code |
| --- | --- |
| **MCP API** | `server/server/app.py` (`/mcp` POST/GET), `server/server/mcp_tools.py` |
| **Accounts & Auth** | `server/app/api/accounts.py`, `server/app/api/admin.py`, `server/app/api/deps.py`, `server/app/core/security.py`, `server/app/core/rate_limit.py`, `server/app/domain/identity.py`, `server/app/domain/invites.py`, `server/app/models/` |
| **Phone Relay** | `server/server/app.py` (`/ws/phone`), `server/server/relay_hub.py`, `server/server/session_registry.py`, `server/app/infra/session_registry.py`, legacy `server/termux_relay_v3.py`, `server/phone/` |
| **Safety Governor** | `server/server/governor.py`, `server/server/safety.py`, `/safety/*` in `server/server/app.py`, `server/server/auth.py` (safety-config helpers) |
| **Pattern Library** | `server/server/pattern_routes.py`, `server/server/pattern_store.py`, `server/app/domain/patterns.py`, `server/app/models/library.py`, `server/app/infra/legacy_migration.py` |
| **Android App** | `android/app/src/main/java/com/yingti/app/` (auth, relay, ble, ui, history, patterns), `AndroidManifest.xml` |
| **Android Updater** | `android/app/src/main/java/com/yingti/app/update/` (`UpdateSources.kt`, `ApkVerifier.kt`, `UpdateInstaller.kt`) |
| **Deployment & CI** | `deploy/setup-server.sh`, `server/Dockerfile`, `server/docker-compose.yml`, `server/app/cli.py`, `server/app/config.py`, `.github/workflows/`, `scripts/` |

## Entry points and who can reach them

- `POST /mcp` — Bearer `aiu_agent_…` token or a session JWT (`_resolve_mcp_user`); per-IP ban + rate limit, per-user command rate limit. `GET /mcp` SSE keep-alive (no auth check observed).
- `WS /ws/phone` — must present a `kind='phone'` token (§5 of architecture doc).
- `/auth/register` (invite code; `REGISTRATION_OPEN` default false), `/auth/login` → 24h session JWT (HS256, `SB_SECRET_KEY`).
- `/api/me`, `/api/tokens` — session JWT only (human actions: create/revoke tokens).
- `/admin/invites`, `/admin/users/*` — `require_admin` (owner).
- `/safety/config`, `/safety/status`, pattern routes — authenticated users, scoped to own `user_id`.
- Host only: `python -m app.cli` (create-user / tokens / doctor), `deploy/setup-server.sh`.
- Phone: app receives relay commands from the server; installs APKs fetched from GitHub or mirror proxies (`REQUEST_INSTALL_PACKAGES`); `usesCleartextTraffic="true"`.

## Assets and data sensitivity

- **[user] User data — patterns, activity history, safety config — named as the most important asset.** Safety config also bounds physical output, so tampering with it is a physical-safety issue, not just a data issue.
- Control of a user's device and the integrity of safety limits (governor, intensity caps, dead-man switch, `stop_all` on shutdown).
- Credentials: passwords (bcrypt), API tokens (SHA-256 hashed, `aiu_<kind>_…`), session JWTs, `SB_SECRET_KEY`, app-stored credentials (EncryptedSharedPreferences), APK signing secrets in CI.
- Owner/admin privileges (invites, user activation, password reset).
- Integrity of the Android app update.
- Highly intimate usage data overall: any leak is sensitive.

## Attacker profiles

**[user] All of the following matter:**
- Unauthenticated internet attackers reaching the reverse-proxied server.
- Invited (authenticated) users trying to reach other users' devices, data, tokens, or admin rights (cross-tenant / privilege escalation).
- A malicious or prompt-injected AI agent holding a valid agent token, trying to exceed safety limits, reach other tenants, or escalate beyond the `agent` kind.

Also considered (inferred, not ruled out by the user): network attackers on the phone↔server path, and anyone able to tamper with the APK update channel (third-party mirrors).

## Existing controls

- Single principal resolver (`resolve_principal`); token kinds do not cross (`Principal.allows`); tokens hashed, plaintext shown once.
- IP ban + rate limits (`app/core/rate_limit.py`); client IP taken from `X-Forwarded-For` first entry (`app/api/deps.py::client_ip`) — trust depends on the proxy.
- Registration closed by default; invite codes.
- Safety governor (intensity×duration heat estimate), repeat/duration caps, heartbeat + dead-man switch, `stop_all` on SIGTERM.
- Default bind to loopback; `SB_SECRET_KEY` required at startup; CORS configurable (`SB_CORS_ORIGINS`, default `*` in `app/config.py`).
- APK install checks: package name, versionCode, signing-certificate match (`ApkVerifier`); comments claim SHA-256 verification.
- Gitleaks + `scripts/check-leaks.sh` in CI; repo red lines R1–R4 (`AGENTS.md`).

## Out of scope / accepted risk

**[user] Nothing is out of scope.** Cleartext LAN/VPN mode, direct port exposure, the legacy Termux relay, and DoS all remain in scope.

## Open questions

- Is the reverse proxy guaranteed to overwrite `X-Forwarded-For`? (affects IP-ban/rate-limit bypass findings)
- Production `SB_CORS_ORIGINS` value.
- No separate infrastructure repository; deployment facts come only from `deploy/` and `server/docker-compose.yml`.
