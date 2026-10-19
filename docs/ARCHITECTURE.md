# Architecture

An nginx router (in `docker-compose`) fronts three components on one origin:

- **Datapath** — `/gateway/{...}` (nginx strips the prefix → `/v1/{...}`) transparently proxies
  the Anthropic API for Claude Code. Served by the Go **gateway**. The Kotlin **service** still
  contains a full datapath, kept as an instant rollback: flipping one nginx `proxy_pass` back to
  `service:8787` reverts it.
- **API routing** — `/routing/openai/{...}` and `/routing/anthropic/{...}` expose the standard
  OpenAI and Anthropic contracts to arbitrary clients, served from the same account pool by the
  same Go **gateway** binary, mounted under those path prefixes. Usage is tagged `source="routing"` and
  metered against a separate per-user daily limit.
- **Management API** — `/api/*` REST endpoints on the Kotlin **service**.
- **SPA** — the React admin UI, static files served at `/` (react-router).

The Kotlin service (`service/`) owns all business logic and state; it no longer serves the UI.
The Go gateways (`gateway/`, one module) are a stateless data plane: they resolve each request
against the service's private `/internal/*` control API — which returns an ordered candidate list
with decrypted upstream credentials — forward to Anthropic, relay the stream, and report usage
back. They never touch the database.

## Request lifecycle (datapath)

`proxy/ProxyRoutes.kt` → `ProxyEngine.handle` → `UpstreamForwarder.forward`:

1. **Inbound auth.** Extract the proxy token from `Authorization: Bearer` or `x-api-key`;
   resolve it to a user (`ProxyTokenRepo`, SHA-256 lookup). Require `PROXY_USE`.
2. **Scope.** Admins may use any account; others are limited to their granted account groups
   (ungrouped accounts are always available).
3. **Read body** once into a `ByteArray` (buffered so it can be replayed across accounts).
4. **Free paths** (`count_tokens`, `/v1/models`) bypass limit checks and use any account.
5. **Daily USD limit.** If the user has one and today's spend ≥ limit → `429` with headers.
6. **Account selection.** `AccountPool.selectionOrder(allowedGroups)` returns candidates in
   order (under-threshold by priority first, then over-threshold for fallback).
7. **Forward with retry.** For each candidate, `UpstreamForwarder.forward` swaps in the
   account's credentials and calls Anthropic. `429`/`5xx`/`401` on a non-last account → try the
   next; the **last** account's real response (including `429`/`5xx` + `retry-after`) is passed
   straight through so the client sees the true status.
8. **Relay + meter.** On success the response is streamed back:
   - **SSE** (`text/event-stream`): flush the head immediately, then stream chunks with
     `readAvailable` while `SseUsageScanner` tees token counts out of the stream, injecting
     `: keep-alive\n\n` comments during upstream silence (see `CLAUDE.md` for why this matters).
   - **JSON** (single message): buffered, usage parsed from the `usage` object.
   - Usage + USD cost recorded via `UsageRepo`/`ModelPriceRepo`.
9. **Live limits.** Every upstream response's `anthropic-ratelimit-*` headers update the
   account's window state (`RateLimitHeaders` → `AccountPool.updateLimit` → `WindowSnapshotRepo`).

## Account selection & rotation

- Accounts have `priority`, `threshold` (0..1), `coefficient` (×1/×5/×20 capacity weight),
  `enabled`, `health`, and per-window live `utilization`.
- **Usage fraction** driving selection = max utilization across known windows (`LimitState`).
- Order: accounts **under** their threshold first (by priority), then the ones that opted into
  `over_threshold` (**fallback**), then over-threshold accounts without the flag as a last resort
  — but only while upstream still reports room (no window `REJECTED` or at utilization 1.0).
  Hard rate-limited accounts are parked until `rateLimitedUntil`.
- `TokenRefresher` refreshes OAuth access tokens in the background. A failed refresh parks the
  account for 30 min, doubling up to 6 h; an `invalid_grant` (expired/revoked refresh token) goes
  straight to the 6 h cap, since only a fresh "Login with Claude" can fix it. Re-authorizing the
  account (**Edit → Re-authorize**, which stores a new refresh token) clears the wait at once. `LimitScheduler`/`LimitProbe`
  periodically probe accounts to keep window state fresh even when idle.

## Data model (Exposed, `db/Tables.kt`)

- `users`, `roles`, `role_permissions`, `user_roles` — RBAC.
- `accounts` (priority/threshold/coefficient/health/type/groupId/clientId) + `account_secrets`
  (AES-256-GCM ciphertext), `account_limits` (per-window utilization), `window_snapshots`
  (time series for the usage graphs).
- `account_groups`, `user_group_access` — per-user routing scope.
- `proxy_tokens` / `routing_tokens` (SHA-256 hash + owner + `enabled`; routing tokens also carry
  an optional static `system_prompt`, proxy tokens an optional `default_model` forced onto every
  request — `[1m]` suffix = the 1M-context beta). Separate namespaces: a `cxp_` never authenticates routing
  and vice versa.
- `usage_events` (input/output/cache_read/cache_write tokens — plus the 1h-TTL slice of the cache
  writes, web search/fetch counts and a `fast` flag — cost, model, httpStatus, userId, accountId,
  `source` = proxy|routing|chat, `token_id` attributing spend to the inbound token),
  `model_prices` (5 per-1M-token prices per model pattern + `fast_multiplier` + `web_search_price`).
- `mcp_tool_calls` — one row per (request, MCP tool) parsed out of the response stream.
- `chats`, `chat_messages`, `chat_attachments` (bytes in the DB — the deploy has one persistent
  volume and attachments must survive a redeploy), `chat_memories`, `chat_settings` — the built-in
  chat. Everything is owner-scoped; temporary chats are never written here at all.
- `settings`, `oauth_add_sessions` (PKCE state for "Login with Claude").

Postgres in production (`DATABASE_URL` set); SQLite fallback otherwise. Use Exposed `upsert`
(cross-DB); clear referencing rows before deletes (Postgres FKs).

## Cost model

Per model pattern, `model_prices` stores USD-per-million-token prices for input, output,
cache_read and cache_write **at each TTL** (5-minute and 1-hour), plus two charges that are not
per token: `fast_multiplier` (fast mode is the same model on a premium tier) and
`web_search_price` (server-side searches are billed per call).

`ModelPriceRepo.costOf(model, BilledUsage)` is the **only** place a response turns into money:

```
cost = (input×in + output×out + cache_read×cr + cache_write_5m×cw5m + cache_write_1h×cw1h) / 1M
       × (fast ? fast_multiplier : 1)
     + web_searches × web_search_price
```

The 1h tier is not a rounding detail — Anthropic charges 2× input for it against 1.25× for 5m, and
Claude Code ≥2.1 caches its main-loop prefix with `ttl:"1h"`, so most cache-write spend lands
there. The gateway reports the split (`usage.cache_creation.ephemeral_1h_input_tokens`), the
`speed` field and `server_tool_use` counts alongside the flat totals.

`UsageRepo.record` persists the cost that call returns and hands the same value back to the
daily-spend counter, so the number in the statistics and the number a limit is enforced against
are one number by construction. Per-user daily spend is summed from `usage_events` since 00:00 UTC
and compared to the user's optional `dailyCostLimit` (plus `dailyRoutingCostLimit` for the routing
datapath and `dailyChatCostLimit` for the built-in chat). Note the deliberate split: **limits** are enforced on UTC days, while every **displayed**
statistic — charts and "today" counters alike — is sliced on the viewer's timezone (`tz` query
param). Figures the UI shows against a limit are labelled UTC for that reason.

## Built-in chat (`chat/`, `/api/chat/…`, SPA `/chat`)

A third datapath alongside the proxy and the routing gateways, and the only one that speaks to a
browser rather than an SDK. `ChatEngine` builds an Anthropic Messages request from the stored
transcript, forwards it across the same ordered candidate list the proxy uses (personal accounts
first unless the user flipped the preference, group scope respected), and relays the upstream SSE
to the browser as its own small event protocol (`start`, `delta`, `thinking`, `tool`, `reset`,
`done`, `error`). Usage lands in `usage_events` with `source="chat"` and meters
`users.daily_chat_cost_limit`, so chat spend and Claude Code spend never eat each other's quota.

What lives where:

- `chat/ChatPrompt.kt` — the request body. The **first** system block is the mandatory Claude Code
  prompt (OAuth accounts reject anything else); a second block reframes the assistant for a chat
  window, then memory, the user's custom instructions and the per-chat prompt follow. Attachments
  become `image` / `document` blocks, or are inlined as tagged text for source files.
- `chat/ChatSse.kt` — an incremental Anthropic SSE reader. Token accounting stays with the shared
  `SseUsageScanner`, which reads the same bytes independently, so the parser is never the source of
  truth for the bill.
- `chat/ChatMemory.kt` + `chat_memories` — durable facts about a user, injected into every chat
  that opts in. Written by hand or by a background extractor after a turn; fully visible and
  editable in the UI.
- `chat/ShareImport.kt` — reads a public ChatGPT/Claude share link (or a pasted export) into a new
  conversation. The fetch tries several endpoints because share pages are not an API; the parsing
  of each shape is pure and unit-tested.
- **Temporary chats** never touch the database: the browser holds the transcript and sends it with
  each turn.

Streaming is as timing-sensitive here as on the Anthropic datapath, so `/api/chat/stream` gets its
own nginx location with `proxy_buffering off` (see `deploy/nginx/nginx.conf`).

## Auth & RBAC

- **UI/API:** signed session cookie (`auth/Security.kt`), bcrypt password hashing
  (`auth/Passwords.kt`). Permissions resolved from roles (`model/Models.kt`, `RoleRepo`/`UserRepo`).
- **Datapath:** proxy tokens (not sessions). A token maps to a user whose permissions and group
  access gate proxying.

## Frontend

React 18 + Vite + TS in `frontend/src/`, built to `frontend/dist` and served by the `front`
image. Section URLs use **react-router-dom** (`/chat`, `/dashboard`, `/my/accounts`, `/my/stats`,
`/stats`, `/tokens`, `/pricing`, `/users`); nginx `try_files` falls unknown paths back to
`index.html`. Pages: Chat, Dashboard (pool), Tokens, API Routing, Stats (cost + window-utilization
SVG charts), MyStats / UserStats (the shared `UserStatsView`), MyAccounts, ModelPricing, Users,
Login. The chat page takes over the whole viewport (`.main-scroll.full`) and renders assistant
output through `chat/Markdown.tsx` + `chat/highlight.ts` — a hand-rolled markdown renderer and
syntax highlighter that emit React nodes only, so model output can never inject markup. Charts are hand-rolled SVG in `Chart.tsx`; shared
components in `ui.tsx`; API client + types in `api.ts`. In dev, Vite (`:5173`) proxies `/api`,
`/gateway`, `/v1`, `/healthz` to the service; in production everything is same-origin via nginx.

## Server engine tuning (`Application.kt`)

Netty is configured with raised `maxInitialLineLength` / `maxHeaderSize` / `maxChunkSize` —
Claude Code sends many/large headers (Stainless `x-stainless-*`, long `anthropic-beta`, big
tokens) that the ~8 KB defaults would reject at the decoder (→ 502 before the handler runs).
