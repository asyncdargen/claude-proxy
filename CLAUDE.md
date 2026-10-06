# CLAUDE.md

Guidance for AI agents (Claude Code) working in this repository. Keep it current when
architecture or deploy steps change.

## What this is

**claude-proxy** — a multi-account rotating reverse proxy for Claude Code / the Anthropic
API. It stores several Anthropic accounts (OAuth subscriptions and/or API keys), routes each
request to the highest-priority account that still has headroom, falls back when all are
saturated, tracks USD cost and rolling-window limits, and ships a React admin UI with
role-based access control. Production shape: a TLS-terminating host proxy (nginx/Caddy, optionally
behind a CDN) in front of an in-compose nginx router on `127.0.0.1:8080`.

## Anthropic egress

`ANTHROPIC_PROXY_URL` is an optional dedicated HTTP CONNECT proxy shared by Go Anthropic
transports and the service Anthropic HTTP client (OAuth lifecycle, probes, fallback/chat).
It never inherits generic proxy environment settings, never falls back to direct when set,
and does not change internal control routes. Proxy credentials must not
reach the upstream; Go tests use local CONNECT/origin servers and service tests cover the
authenticated tunnel. Browser sign-in/telemetry/app updates are not server traffic.

## Components & URL routing

The app is split into self-contained components fronted by one nginx router (in `docker-compose`,
listening on `127.0.0.1:8080`). A host reverse proxy terminates TLS and proxies to `:8080`.

| Public path   | Component            | Notes                                                    |
|---------------|----------------------|----------------------------------------------------------|
| `/`           | **frontend** (SPA)   | React admin UI **+ the chat UI**, static, served by nginx (React Router) |
| `/api/…`      | **service** (Kotlin) | Management REST API **+ the chat API/datapath** (`/api/chat/…`) |
| `/gateway/…`  | **gateway** (Go)     | Anthropic datapath (`/gateway/v1/…`), served by the Go **gateway** (Spec B). It resolves each request against the service's private `/internal/*` control API, forwards to Anthropic, relays SSE, and reports usage back. The Kotlin datapath (`service:8787`) stays running as an instant rollback (revert the two nginx `proxy_pass` targets). |
| `/routing/openai/…`    | **gateway** (Go, same container) | OpenAI Chat Completions API emulated over Claude Code subscriptions. Translates OpenAI↔Anthropic (streaming + tool calls), resolves via the control API with `source="routing"`. base_url = `<origin>/routing/openai/v1`. |
| `/routing/anthropic/…` | **gateway** (Go, same container) | Native Anthropic Messages API served from Claude Code subscriptions (injects the Claude Code system prompt), `source="routing"`. base_url = `<origin>/routing/anthropic`. |

The routing gateways expose standard OpenAI/Anthropic API contracts to arbitrary clients but serve
them through the same account pool as the Claude Code proxy. They authenticate with **routing
tokens** (`cxr_…`, gated by `ROUTING_USE`), meter spend against a **separate per-user daily USD
limit** (`users.daily_routing_cost_limit`), and tag usage rows `source="routing"` (proxy rows are
`source="proxy"`, chat rows `source="chat"`) so the three datapaths share stats but keep
independent limits. A routing token
may carry a **static system prompt** (`routing_tokens.system_prompt`, editable on the API Routing
page): the service returns it from `/internal/resolve` and `routing.go` injects it via
`ccident.InsertStaticPrompt` right after the mandatory Claude Code block — ahead of (higher
priority than) any system content arriving in the API request. All three gateways
are one Go module (`gateway/`, binaries under `cmd/`); they never touch Postgres.

Top-level dirs: `frontend/` (React) · `service/` (Kotlin business logic + control API + Kotlin
datapath/rollback) · `gateway/` (Go — one module: the Claude Code datapath at `gateway/main.go`
serving all three paths off one port, split by prefix in `newMux`) · `deploy/` (nginx config
+ Dockerfiles).
Containers: `claude-proxy-{nginx,front,service,gateway,db}` — the whole Go data plane is one
image and one container.
`docker-compose.yml`, `.env.example`, `.dockerignore` stay at the repo root (compose sits next
to server runtime state). See `docs/superpowers/specs/2026-07-12-repo-restructure-nginx-routing-design.md`.

## Stack

- **Backend:** Kotlin 2.2, Ktor 3.2 (Netty engine), Exposed 0.58 ORM, HikariCP, JVM target 21.
- **Gateway:** Go 1.23 (stdlib `net/http` only, no framework) — the datapath in Spec B.
- **DB:** PostgreSQL 16 in production; SQLite fallback when `DATABASE_URL` is unset.
- **Cache:** in-process `MemoryCache` (service-side TTL map) — hot-path accelerator (token
  resolve, daily spend), never a source of truth: every miss falls through to the DB, a restart
  is just a cold cache. Single-instance by design; if the service is ever scaled horizontally
  this must become a shared cache again (a Redis implementation lived here until 2026-07 — see
  git history).
- **Frontend:** React 18 + Vite + TypeScript, **react-router-dom** for section URLs, hand-rolled
  SVG charts, no UI framework. Builds to `frontend/dist`; the nginx router serves it (same origin
  as the API — no CORS in prod).
- **Packaging:** `docker-compose` stack — `nginx` (SPA + router) + `service` (Kotlin fat jar,
  UI **not** baked in) + `postgres`.

## Repository layout (`service/src/main/kotlin/org/claudeproxy/`)

| Path | Responsibility |
|------|----------------|
| `Application.kt` | Entry point. `embeddedServer(Netty)` with raised header/line limits; installs ContentNegotiation, ForwardedHeaders, CORS, StatusPages, security; wires routing. |
| `Config.kt` | Config from env vars / `.env` (loaded into system properties). |
| `proxy/` | **Datapath.** `ProxyRoutes` (inbound auth → account select → forward, `/v1/{...}`), `UpstreamForwarder` (forwards to Anthropic, relays SSE, records usage), `Http` (upstream CIO client), `SseUsageScanner` (token counting from the stream). |
| `accounts/` | `AccountPool` (selection/rotation/fallback), `AccountRepo`, `RateLimitHeaders` (parse `anthropic-ratelimit-*`), `TokenRefresher` (background OAuth refresh), `LimitProbe`/`LimitScheduler`, `UpstreamAuth`, `Secrets`. |
| `api/` | `AdminRoutes` (REST API for the UI), `Dtos`; `InternalRoutes` + `InternalDtos` (the private `/internal/resolve` + `/internal/usage` control API for the Go gateway, gated by `X-Internal-Token`). |
| `datapath/` | `DatapathService` — the reusable resolve-a-request-into-an-ordered-plan + apply-an-outcome logic, shared by the Kotlin datapath and the control API (selection/crypto/limit bookkeeping stays here). |
| `chat/` | **The built-in chat.** `ChatEngine` (its own datapath: build → select → forward → relay SSE → record), `ChatPrompt` (system blocks + content blocks, pure), `ChatSse` (Anthropic SSE → typed events, pure), `ChatModels` (picker catalogue), `ChatMemory` (extractor output parsing), `ShareImport` (ChatGPT/Claude share links). |
| `cache/` | `MemoryCache` — in-process TTL cache with DB fallback (token resolve, daily spend); evicted on token delete. |
| `auth/` | `Security` (session cookies), `Passwords` (bcrypt). |
| `db/` | `Database` (init + seed), `Tables` (Exposed schema), `Crypto` (AES-256-GCM for account secrets at rest). |
| `model/Models.kt` | `Permission`/`AccountType`/`WindowKind`/health enums + serializable DTOs. |
| `repo/` | Data access: Users, Roles, Groups, ProxyTokens, Usage, ModelPrices, WindowSnapshots, Settings, OAuthAdd. |
| `oauth/ClaudeOAuth.kt` | PKCE "Login with Claude" flow to add accounts. |

Frontend lives in `frontend/src/` (`App.tsx` = router shell, `api.ts`, `Chart.tsx`, `ui.tsx`,
`pages/*`, `chat/*`). Section URLs: `/chat` (+ `/chat/:id`), `/dashboard`, `/my/accounts`,
`/my/stats`, `/stats`, `/tokens`, `/pricing`, `/users` (react-router; nginx `try_files` falls
unknown paths back to `index.html`). The chat page opts out of the shell's padding/scrolling
(`.main-scroll.full`) and lays out its own three regions; `chat/Markdown.tsx` +
`chat/highlight.ts` are a hand-rolled renderer/highlighter (React nodes only — never
`dangerouslySetInnerHTML`, so model output can't inject markup).

## Build / run / test

```bash
cd service && ./gradlew run    # service only, reads .env, serves BIND_HOST:PORT (default 127.0.0.1:8787)
cd frontend && pnpm dev        # Vite HMR on :5173, proxies /api /gateway /v1 /healthz to the service
cd service && ./gradlew fatJar # service fat jar (UI NOT baked in) -> service/build/libs/claude-proxy-<v>-all.jar
cd service && ./gradlew test   # JUnit
cd gateway && go test ./...    # Go gateway unit tests (go vet ./... too)
cd gateway && go run .         # whole data plane on :9000 — /v1 datapath + /routing/{openai,anthropic}
                               # (needs SERVICE_URL + INTERNAL_TOKEN; DEFAULT_MODEL optional)
docker-compose up -d --build   # nginx (:8080) + front + service + gateway(+openai/anthropic) + postgres
```

The Gradle project now lives under `service/` — run `./gradlew` from there (or `service/gradlew
-p service …`). The frontend build is fully decoupled (nginx owns it); there is no `bundle` task.

## Deployment — read `docs/DEPLOY.md` before deploying

A Docker Compose stack on one host, behind a TLS-terminating reverse proxy that points at the
**in-compose nginx router** on `127.0.0.1:8080`; the compose nginx fans out to `service` and the
Go gateways. `docs/DEPLOY.md` uses placeholders (`root@YOUR_SERVER`, `/opt/claude-proxy`,
`proxy.example.com`) — real hostnames and paths belong in an operator-private note, never in the
repo.

> ⚠️ **NEVER `rsync --delete` into the deploy directory.** It holds runtime state
> that is NOT in the repo: `pgdata/` (the entire Postgres DB, bind-mounted) and `.env` (server
> secrets, incl. `DATABASE_PASSWORD`). A `--delete` once wiped the database. Sync **only
> source**, without `--delete`, excluding `pgdata`, `.env`, `data`. Full recipe + safety in
> `docs/DEPLOY.md`.

## Config precedence gotcha

Effective config = Docker image `ENV` < compose `env_file: .env` < compose `environment:`.
Because `.env` ships `BIND_HOST=127.0.0.1` (a local default), `docker-compose.yml` pins an
override in the `service` `environment:` block — **do not remove it**:

- `BIND_HOST: "0.0.0.0"` — the service must bind all interfaces inside the container, or the
  compose **nginx** (reaching it as `service:8787` over the compose network) can't connect
  (→ nginx 502, healthcheck fails).

`PUBLIC_DOMAIN` is passed through from `.env` (`PUBLIC_DOMAIN: ${PUBLIC_DOMAIN:-}`) — it sets the
token base URL and the CORS `allowHost`. In prod the SPA is same-origin (served by nginx), so CORS
is moot there; the setting still matters for the Vite dev origin and the displayed base URL.
Blank/unset is valid: the UI then falls back to the browser's current origin.

## Domain concepts (see `docs/ARCHITECTURE.md` for depth)

- **Account types:** `OAUTH` (access+refresh, auto-refreshed), `OAUTH_STATIC` (access only),
  `API_KEY` (`x-api-key`). Secrets encrypted with `MASTER_KEY` (AES-256-GCM).
- **Global vs personal accounts:** `accounts.owner_id` = null → **global** (shared pool, the
  Dashboard); non-null → a user's **personal** account. The pool holds both; selection tries a
  user's personal accounts first (own tier), then the global pool (see Rotation). Personal
  accounts are never grouped, are excluded from *all* global stats (`UsageRepo` filters
  personal account ids; `pool.snapshotGlobal()`), and their spend does **not** count toward the
  per-user daily USD limit — so a user over the shared limit can still route through their own
  accounts. Managed at `/api/my/accounts/*` (self, `ACCOUNTS_OWN_MANAGE`) and
  `/api/users/{id}/accounts/*` (admin oversight, `USERS_MANAGE`).
- **Rotation:** strictly by `priority`; move to the next account once the active one's window
  usage crosses its `threshold`. The threshold is a **rotation point, not a hard stop** — within
  a tier the order is: under-threshold (by priority), then accounts with the opt-in
  **`over_threshold`** flag (**fallback**, by priority), then — last resort — the over-threshold
  accounts *without* the flag, as long as upstream still reports room (no window `REJECTED`, none
  at utilization 1.0). Dropping that last group outright is what once answered 503 to every
  request while an account still had a fifth of its 5h window; only a genuinely exhausted account
  leaves selection now, until its window resets. `coefficient` (×1/×5/×20) weights pool capacity. Personal accounts form a
  preferred tier ordered ahead of the global tier — unless the user flips
  `users.prefer_global_pool` (self-service toggle on "My Accounts", gated by
  `ACCOUNTS_ORDER_TOGGLE`), which routes through the global pool first and falls back to
  personal accounts.
- **Windows:** `FIVE_HOUR` ("5h") + `WEEKLY` ("7d"), read from
  `anthropic-ratelimit-unified-{5h,7d}-utilization` (0..1) response headers. Subscriptions
  report **utilization**, not remaining/limit.
- **Usage credits + grace** (`RateLimitHeaders` → `LimitState.overage` / `.grace`, in memory only,
  surfaced on `AccountDto`): Anthropic's "extra usage" lets a saturated subscription keep serving
  and bills the overflow as money — `overage-status`, `-in-use`, `-period-monthly-utilization`,
  `-period-channel-utilization`, `-reset`, `-disabled-reason`, plus `7d_oi-utilization` (weekly
  *including* credits) and the free `grace-{status,5h,7d}` family. Read-only: selection is
  unchanged (an account on credits is already usable via the `over_threshold` flag), but the
  Dashboard shows an "on credits NN%" badge and the weekly cell reveals the with-credits figure,
  so a 100% weekly window is distinguishable from a dead account.
- **Cost model:** per-model USD pricing in `ModelPrices` — input / output / cache_read /
  **cache_write_5m** / **cache_write_1h** per 1M tokens, plus **`fast_multiplier`** (× on all
  token prices when a response was served with `speed: "fast"`) and **`web_search_price`** (USD
  per server-side search, billed per call). The 1h cache tier matters: Anthropic charges 2× input
  for it against 1.25× for 5m, and Claude Code ≥2.1 writes its main-loop prefix with `ttl:"1h"`.
  A single `ModelPriceRepo.costOf(model, BilledUsage)` is the only place a response becomes money;
  `UsageRepo.record` stores what it returns and hands the same number to the daily-limit counter,
  so stats and limits cannot drift. `usage_events` keeps the 1h slice, web search/fetch counts and
  the fast flag alongside the totals so a stored cost stays auditable.
- **Response-side model attribution:** the gateway prices what the *response* says answered
  (`message_start.message.model`), falling back to the request's model. Server-side refusal
  fallback can hand the turn to a different model mid-call, and paying the requested model's rate
  would be wrong.
- **MCP-call accounting:** the Claude Code gateway counts MCP tool invocations — client-side
  `tool_use` blocks named `mcp__server__tool` *and* server-side `mcp_tool_use` blocks (the
  `mcp_servers` connector), normalized to the same key — via one shared SSE parse
  (`internal/proxy/usagescan.go` drives `anthropic.SSEParser`; `mcpscan.go` consumes the events
  next to the usage fold) and the buffered-JSON path. Ships them as `UsageReport.mcpCalls`; the
  service writes one `mcp_tool_calls` row per (request, tool). Served by `/api/stats/mine/mcp` +
  `/api/users/{id}/stats/mcp`; "MCP tools" block in the shared `UserStatsView`. Proxy datapath
  only (routing gateways don't report it).
- **Upstream identity** (`metadata.user_id` = escaped `{"device_id","account_uuid","session_id"}`):
  each account presents its own. `device_id` is the per-account fingerprint; `account_uuid` is
  the subscription's Anthropic account uuid (`accounts.account_uuid`), taken from the token
  response at "Login with Claude", else backfilled from `/api/oauth/profile` by `TokenRefresher`,
  else set by hand in the account's Edit dialog (inference-only `OAUTH_STATIC` tokens can't read
  the profile). It is **always overwritten** — `""` for API keys and not-yet-known uuids — since
  the client's value names whoever runs the client. The proxy datapath swaps values in place
  (`rewriteBody` / `RequestRewriter`); requests that never came from the CLI — routing
  (`ccident.StampUserID` + `SetClientHeaders`), chat and the limit probe (`ClaudeCodeClient`) —
  get the whole blob plus CC's `User-Agent` (`CLAUDE_CODE_USER_AGENT`), `x-app: cli` and a
  session id stable per (token/user, account, UTC day). Never on `count_tokens`: it rejects
  `metadata`.
- **Free paths** (`count_tokens`, `/v1/models`): no quota, no daily limit, and the resolve returns
  the *whole* try-list (`AccountPool.selectAnyOrder`) so one unhealthy account can't break Claude
  Code's context indicator. `ResolveResponse.free` rides back on the usage report: a **successful
  zero-token** free-path attempt is not recorded at all, keeping per-keystroke token counts out of
  the request counters — failures still are, because a broken `count_tokens` must stay visible.
- **Upstream retry statuses** (`gateway/internal/proxy/forward.go`): 429/401/**403**/500/502/503/529
  move to the next candidate. 403 is account-scoped upstream (suspended or past-due subscription),
  so the pool can still serve the request; it deliberately does *not* mark the account unhealthy —
  a permission-shaped 403 would otherwise park every account in the pool at once.
- **Proxy tokens:** `cxp_...` (Claude Code datapath); **routing tokens:** `cxr_...` (OpenAI/Anthropic
  gateways) — distinct namespaces (a `cxp_` never authenticates routing and vice versa), both stored
  as SHA-256 and presented inbound via `Authorization: Bearer` or `x-api-key`. Both kinds carry
  an `enabled` flag (default true, `PATCH /api/{proxy,routing}-tokens/{id}/enabled`, own tokens
  only): a disabled token is filtered out in `loadByHash`, so it resolves like an unknown token
  (401) on every datapath at once; the switch evicts the resolve cache, so it applies instantly.
  A routing token's settings (enable switch + static system prompt) live in one Edit dialog on
  the API Routing page; creation only takes a name.
- **Token default model** (`proxy_tokens.default_model`, `PATCH /api/proxy-tokens/{id}`, Edit
  dialog on the Proxy Tokens page): a `cxp_` token may force one model onto *every* request it
  makes — Claude Code's background Haiku calls and `count_tokens` included, so the context
  indicator counts against the model that will answer. Free text in Claude Code's notation: a
  trailing `[1m]` is stripped and becomes the `context-1m-2025-08-07` beta, merged into the
  client's `anthropic-beta` (that is all Claude Code itself does with the suffix). The service
  returns it from `/internal/resolve` (`defaultModel`, cached 60s, evicted on edit); the Go
  gateway splices it over the body's *top-level* `model` in place (`proxy/model.go`) — no
  re-marshal of a transcript-sized body, and a nested `"model"` in a tool input is left alone.
  Go datapath only: the Kotlin rollback path forwards the client's model.
- **Viewer-local days:** every time-series endpoint takes an IANA `tz` query param
  (`rangeParams()` in `AdminRoutes`), and so do the **"today" counter** payloads that carry no
  date range — `/stats/mine`, `/users/{id}/stats`, `/stats/models`, `/users/stats/overview` — via
  `zoneParam()` + `UserRepo.startOfDayIn(zone)`. The frontend sends
  `Intl…resolvedOptions().timeZone` on every stats request, so the cards and the charts under them
  are sliced on the same (viewer's) day. Missing/unparseable `tz` falls back to UTC — the
  pre-existing behaviour. **Exception, load-bearing:** the per-user **daily USD limits stay on UTC
  days** (`UserRepo.startOfUtcDay`, enforced in `DatapathService`/`ProxyRoutes`), so everything
  read *against a limit* keeps the UTC basis — `MyStatsPayload.proxyTodayCost`/`routingTodayCost`/`chatTodayCost`,
  `UserOverviewRow.todayProxyCost`/`todayRoutingCost`, and the Users-list "Spent today" column.
  Those are labelled UTC in the UI (the limit card spells out its own 00:00 UTC countdown);
  `UsageRepo.overviewByUser` takes both day starts for exactly this reason. Guarded by
  `repo/ViewerDayStatsTest`.
- **Window utilization charts** (`/stats/windows`, `/stats/mine/…`, `/users/{id}/…`): 30-min
  buckets (matching the ~30-min `LimitProbe` sampling), capped at 336 — longer ranges coarsen the
  bucket width. `planWindowBuckets` sizes the grid and **truncates it at `now`** when the range
  ends today. Do not "simplify" that back to the end of the last calendar day: `aggregateWindows`
  carries the last reading forward across gaps, so trailing empty buckets draw a flat line hours
  into the future that reads as live data. The width is derived from the full span *before*
  truncation, so dropping the tail never re-scales the buckets. When the grid is truncated (i.e. it
  reaches the present), one extra **live column** is appended: `liveWindowSamples` reads the pool's
  in-memory `LimitState` — fresher than anything persisted, since `WindowSnapshotRepo` throttles
  writes to one per (account, window) per minute — and labels it with the actual clock time. Those
  synthetic samples are stamped *mid* extra-bucket, not on its boundary: the boundary is a truncated
  long, so for non-integral widths a boundary sample floors back and gets averaged into the last
  real bucket, overwriting a genuine reading. Guarded by `api/WindowBucketPlanTest`.
- **Window burn per day** (`/stats/window-daily`, `/stats/mine/…`, `/users/{id}/…`): how much of
  each limit window was *consumed* per day, in window-fractions (1.0 = one full window). Computed
  in `aggregateWindowDaily` as the sum of positive steps of the utilization series — a drop means
  the window reset, so the fresh reading counts in full, which is why a day with several 5h resets
  reads well past 100%. Samples are fetched with a 24h lookback so the first day continues the
  previous series; the first sample of a series is a baseline, never spend.
- **Active sessions:** `datapath/ActiveSessions` counts requests streaming from Anthropic right
  now. The Go gateway mints a session id per request (`control.Client.Resolve`), the service opens
  the entry on a successful resolve and closes it on `/internal/session-end` (fire-and-forget from
  the gateway's `defer`); the Kotlin datapath wraps its own handler. Entries expire after 30 min so
  a lost close can't pin the gauge. In-process and unpersisted — a restart correctly reads zero.
- **Built-in chat** (`chat/`, `/api/chat/…`, SPA `/chat`): a full chat client — stored
  conversations with search, attachments (images/PDF native, text inlined), streaming, per-user
  **memory**, custom instructions, **temporary chats** (never persisted; their transcript rides in
  the request body), and **import** from a public ChatGPT/Claude share link. It is a **third
  datapath**: it selects from the same pool with the same personal-first/group rules, but tags
  usage `source="chat"` and meters `users.daily_chat_cost_limit` — so a chat session can't eat the
  quota Claude Code runs on. Gated by `CHAT_USE`. `ChatEngine` mirrors the proxy datapath's
  load-bearing SSE behaviour (head flushed immediately, true streaming, keep-alive comments during
  silence) and retries the next account while nothing user-visible has been written; when an
  account dies after emitting only thinking, a `reset` event tells the client to drop it.
  Auto-titling and memory extraction are fire-and-forget Haiku calls after the turn — recorded as
  chat usage like everything else, never billed invisibly. **The whole `/api/chat/` prefix needs
  the datapath's nginx settings** (`proxy_stream.conf`): buffering withholds the head exactly as it
  did for the Anthropic datapath, and the 60s default read timeout kills the slow operations —
  compacting a conversation is a full model call over its transcript, and the browser then gets an
  HTML 504 where it expected JSON. `/continue` answers as a stream with keep-alives for the same
  reason.
- **Chat prompt caching** (`ChatPrompt.cacheMarks`): every request marks the last system block
  (tools + system are byte-identical across a conversation's turns) and a **rolling pair** of
  messages — the newest turn, plus one two turns back so an expired write still leaves an older
  prefix to read. Three breakpoints, inside Anthropic's limit of four. Writes are priced at the 5m
  tier, which `ModelPriceRepo` already bills; below the model's minimum cacheable length the
  markers are simply ignored upstream.
- **Turn timestamps:** each user turn is prefixed with a `[sent <date> <time>, <tz>]` text block on
  the viewer's clock (`ChatStreamRequest.tz`), and the system prompt tells the model to read it as
  metadata — the newest one is "now". Deliberately **not** in the system prefix: a clock up there
  would change the cached prefix on every request and turn every cache read into a miss.
- **`ChatRepo.copy`** duplicates a conversation, transcript and attachment *bytes* included, so the
  two are independent — deleting either must not knock a file out of the other. Timestamps are
  carried over, since it is the same conversation.
- **Rewind vs retry vs edit** (chat UI): *retry* re-answers the same turn, *edit* rewrites a user
  turn and resends, *rewind* cuts back to a user turn and returns it — text and files — to the
  composer **unsent**. That last one is why `ChatRepo.truncateFrom` **detaches** attachments
  (`message_id = null`) instead of deleting them; the orphan pruner sweeps whatever is never
  re-sent. **`truncateFrom` verifies the id is a real message of that chat** — the cut is
  `id >= messageId`, so an out-of-range id (a client-side optimistic placeholder, say) deletes the
  entire conversation. That happened to a live chat; the client also never sends a placeholder now,
  and re-reads the thread after a stream dies.
- **"Continue in a new chat"** (`ChatEngine.continueInNewChat`, `POST /chat/chats/{id}/continue`):
  compacts the transcript upstream into a handover and opens a fresh chat whose first assistant
  message *is* that handover — visible, editable, and replayed like any other turn. Deliberately
  not also copied into the chat's system prompt: the model would read the same text twice on every
  request. Synchronous, unlike the other chores — the user is waiting on the new chat, and a silent
  failure would open one that has quietly lost the thread.
- **"Ask about this chat"** (`chat/AskPanel.tsx`): a side question answered against the open
  conversation's transcript, run as a **temporary** turn — so it costs money but leaves no trace
  unless kept. "Add to chat" appends the pair through `POST /chat/chats/{id}/messages`, which does
  no upstream call at all: the answer already exists and was already billed.
- **Dialogs and menus render through a portal** (`Modal`, `AnchoredMenu` in `ui.tsx`). Not
  tidiness: the chat page nests them inside a column that clips its overflow and stacks a drawer,
  a scrim and a sticky header, so an in-place dialog ends up visible but unclickable, and the row
  menu gets clipped by the scrolling list. `AnchoredMenu` positions against the live trigger and
  closes on scroll rather than chasing it.
- **The thread scrolls by setting `scrollTop` once per animation frame**, and `.chat-scroll` has
  no `scroll-behavior: smooth`. A `scrollIntoView` per token restarts an easing animation on every
  delta, which is what made the transcript judder on a phone mid-answer.
- **Chat on phones:** the app topbar is hidden on `/chat` (`.app.chat-route`) — it repeated the
  page title and ate a row of a mostly-transcript screen; the way back to the rest of the admin is
  the `.leave-chat` button above the conversation list. Every chat field is **16px on mobile**:
  below that iOS zooms on focus and never zooms back out, leaving the whole app scaled up. Those
  rules have to live next to the chat selectors — the generic `input { font-size: 16px }` loses on
  specificity.
- **Permissions** (`model/Models.kt`, ordered least→most): `PROXY_USE`, `ROUTING_USE` (use the
  OpenAI/Anthropic routing gateways + manage `cxr_` tokens), `CHAT_USE` (use the built-in chat UI),
  `STATS_VIEW_OWN`, `STATS_RESET_OWN`,
  `ACCOUNTS_OWN_MANAGE` (manage own personal accounts + "My Accounts" page), `ACCOUNTS_ORDER_TOGGLE`
  (switch personal-vs-global routing order), `POOL_GLOBAL_USE` (route through the shared pool),
  `STATS_VIEW_RECENT`, `STATS_VIEW_ACCOUNTS`, `ACCOUNTS_VIEW`, `STATS_VIEW`, `ACCOUNTS_MANAGE`,
  `USERS_MANAGE`, `ADMIN`. Default roles `user`/`manager` include `ACCOUNTS_OWN_MANAGE` +
  `ACCOUNTS_ORDER_TOGGLE` + `POOL_GLOBAL_USE` + `ROUTING_USE` + `CHAT_USE`. A one-time migration
  (`migrated:chat_use`) grants `CHAT_USE` to every role that already had `PROXY_USE`, so the chat
  is reachable right after an upgrade without hand-editing roles.

## Anthropic upstream specifics (calibrated against live traffic)

- OAuth requests require `Authorization: Bearer sk-ant-oat...`, `anthropic-version: 2023-06-01`,
  `anthropic-beta: oauth-2025-04-20`, and the Claude Code system prompt
  `"You are Claude Code, Anthropic's official CLI for Claude."` — otherwise 400/401.
- Rate-limit headers: `anthropic-ratelimit-unified-5h-utilization` / `-7d-utilization` (0..1),
  `-5h-status`, `-5h-reset` (epoch seconds). No remaining/limit for subscriptions.
- **`overloaded_error` can arrive *after* a 200.** Anthropic accepts the request, then fails it
  with an in-stream `event: error` frame. Both Go datapaths scan for it
  (`anthropic.ErrScan`, shared): if nothing client-visible has been written yet — only
  `: keep-alive` comments, which SSE consumers discard — the next candidate takes the request over
  **on the same open stream**, invisibly to the client (`headSent` threads through
  `forward`/`relaySSE`/`relayStream` so the head is written exactly once). Once real frames are
  out, or no candidate is left, the error is surfaced instead: the proxy datapath writes a
  normalized retryable frame (`midStreamError`), routing lets the translator emit its own error
  shape. Retryable HTTP statuses (429/401/500/502/503/529) still swap accounts before any head is
  sent, as before. The Kotlin rollback datapath does **not** do the in-stream swap — it normalizes
  and lets the client retry (its coroutine relay carries the load-bearing 502 fix; left alone).
- **A stream can also just stop.** Anthropic sometimes goes silent mid-answer and holds the socket
  open — no error frame, no `message_stop`. The keep-alive comments then make the corpse look
  alive, so the client waits minutes and reports "Response stalled mid-stream" over a half-written
  answer. `relaySSE` runs a watchdog (`UPSTREAM_STALL_SECONDS`, default 120, 0 disables): after
  that long without a single *content-bearing* upstream frame it takes the same decision as an
  in-stream error — swap accounts while the client has seen only keep-alives, otherwise close with
  a retryable `overloaded_error` frame. The attempt is recorded as **504**, deliberately not 429:
  parking a healthy account on a window reset because a stream went quiet would cost hours of pool
  capacity. `pingOnly` is what makes the watchdog work at all: measured on a stuck stream, Anthropic
  keeps sending exactly one 39-byte ping every 30s for minutes after the answer stops, so a byte
  counter never fires — pings are relayed but count as neither progress nor client-visible content.
- **One silence is Anthropic's own doing, and the watchdog must not kill it.** Unless a tool opts
  into eager input streaming, the API buffers and validates a tool parameter's *whole value* before
  sending it, and current models emit one key-value pair at a time — so a `Write` whose `content` is
  a 40 KB document opens its `content_block_start` and then sends nothing but pings for minutes.
  Measured: 178s for a 42 KB plan, 191s for a 46 KB spec, 397s for a 109 KB file. At the plain 120s
  budget that died *every time*, after the model's preamble text had already gone out (so no account
  swap either), and the client's retry walked into the same wall — superpowers' `writing-plans`
  reproduced it on demand, re-billing a cold cache write of the whole prompt on each attempt (1.1%
  of all datapath requests, $14.82 in one day). `blockScan` tracks the open content block and grants
  `toolInputStallFactor` (×4) while a tool input is being buffered; everything else keeps the tight
  budget. Both numbers stay well inside Claude Code's own limits (byte watchdog 300s on a gateway
  base URL, event watchdog ~600s with its synthetic pings).
- **The relay writes whole SSE events, never half of one** (`anthropic.SSEFramer`, Kotlin
  `SseFramer`). Upstream chunk boundaries are arbitrary — over HTTP/2 a read routinely ends inside a
  `data:` line — and the relay injects bytes of its own (keep-alive comments, the stall frame, the
  normalized mid-stream error). Injected mid-event, the blank line terminates that event early and
  the client parses `…"text":"hel: keep-alive` → a JSON error, or worse, deltas for a
  `content_block_start` it never saw, which is an unhandled `RangeError` in Claude Code's SSE
  consumer. So the framer holds the trailing fragment until it completes, injection only happens at
  `Pending() == 0`, and a fragment that can never complete is discarded before the terminating
  frame. Nothing is delayed that the client could have used: an SSE reader buffers a partial event
  the same way.
- **The silence *before* the head counts too.** `ResponseHeaderTimeout` is deliberately 0 (Opus
  can think 30s+ before its first byte), so a streaming request used to sit mute for Anthropic's
  whole think time *plus* every retried candidate ahead of it — and whatever fronts the gateway
  times the request out on that silence: behind Cloudflare it became a **524 at 120s** with a
  `499 0` in the nginx log (~2% of datapath requests during a slow upstream spell).
  `doWithEarlyHead` (`EARLY_HEAD_SECONDS`, default 45, 0 disables) opens the client's stream
  itself once the wait passes that mark and keeps sending `: keep-alive` until upstream answers.
  Only for requests that asked for `stream: true` — an event-stream head would be unparseable to a
  client waiting on JSON. The early head costs nothing else: keep-alive comments are discarded by
  SSE consumers, so `headSent` still leaves the stream a blank slate the next account can take
  over, and a non-SSE answer after it closes out through the existing `failAfterHead` path.
- **SSE relay is timing-sensitive.** `UpstreamForwarder` must flush the response head
  *immediately*, stream with `readAvailable` (not the buffering `readRemaining`), and inject
  `: keep-alive\n\n` SSE comments during upstream silence. Adaptive-thinking Opus on a 1M
  context stays silent 30s+ before the first event; a late head or buffering makes clients
  abort → nginx `upstream prematurely closed connection while reading response header` (502)
  and an endless client retry loop. Don't regress this.

## Conventions

- Match the surrounding style; comments explain **why**, not what (see existing files).
- The datapath is fully coroutine/suspend. Use Exposed `upsert` (cross-DB) — `replace` fails on
  Postgres. On delete, clear referencing rows first (Postgres enforces FKs).
- Don't add dependencies without a clear reason. Don't commit secrets — they live in the
  server `.env`.

A successful 2xx limit probe clears the previous persisted 429 cooldown. Error responses, even with rate-limit headers, must preserve it. This permits recovery after an upstream manual quota reset before the old reset time.

`GET /gateway/v1/usage` is an exact nginx route to the service, authenticated by a proxy/routing API token. It returns the next candidate and anonymous in-scope pool quotas without inference or active-session side effects. See docs/TOKEN_USAGE.md.

Browser cookies carry a signed server-enforced 30-day expiry and `users.session_version`. Password changes, account disable and logout revoke all existing sessions in that generation; stale logout cannot revoke a newer generation. Existing cookies are invalid on upgrade and users must log in again. The additive session_version column is initialized by the existing schema setup. API tokens remain independently managed.
