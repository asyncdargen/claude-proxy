# claude-proxy

A self-hosted, multi-account rotating proxy for **Claude Code** and the Anthropic API.

Store several Anthropic accounts (Claude subscriptions via OAuth, and/or API keys). Every request
is routed to the highest-priority account that still has headroom in its rate-limit window; once
that account crosses its configured **threshold**, traffic moves to the next one. When *all*
accounts are saturated, only the accounts you explicitly opted into **fallback** keep serving.
Spend, rolling-window utilization and per-user/per-token statistics are tracked and rendered in a
React admin UI with roles and permissions.

Beyond the Claude Code datapath, the same account pool is exposed through two **API routing
gateways** — an OpenAI Chat Completions endpoint and a native Anthropic Messages endpoint — so
arbitrary clients (SDKs, agent frameworks, IDE plugins) can be pointed at the pool with their own
tokens and their own spend limits.

> **Scope & responsibility.** This is infrastructure for routing *your own* accounts. Sharing
> Anthropic access with third parties may conflict with Anthropic's terms — check them before
> pointing anyone else's client at your instance.

## Quick start (prebuilt images)

Nothing to build — multi-arch (`linux/amd64`, `linux/arm64`) images are published on GHCR.
You need Docker with the Compose plugin.

**One command** — downloads the compose file into `./claude-proxy`, writes a `.env` with random
secrets (printing the admin password once) and starts the stack:

```bash
curl -fsSL https://raw.githubusercontent.com/asyncdargen/claude-proxy/master/scripts/bootstrap.sh | bash
# options: … | bash -s -- --allinone --port 8080 --dir ./claude-proxy
```

**Or by hand** — the split stack (Postgres + service + gateway + web):

```bash
mkdir claude-proxy && cd claude-proxy
base=https://raw.githubusercontent.com/asyncdargen/claude-proxy/master/deploy/compose
curl -fsSLO $base/docker-compose.yml
curl -fsSL $base/.env.example -o .env
# fill in .env: MASTER_KEY, SESSION_SECRET, ADMIN_PASSWORD, DATABASE_PASSWORD, INTERNAL_TOKEN
#   openssl rand -hex 32     # fine for each of them
docker compose up -d
```

…or the **all-in-one** container (Postgres + one image running everything; `INTERNAL_TOKEN` is
optional there):

```bash
curl -fsSLO $base/docker-compose.allinone.yml
docker compose -f docker-compose.allinone.yml up -d
# or with no Postgres at all (SQLite in a volume):
docker run -d -p 127.0.0.1:8080:8080 -v claude-proxy-data:/data \
  -e MASTER_KEY=$(openssl rand -hex 32) -e ADMIN_PASSWORD=change-me ghcr.io/asyncdargen/claude-proxy
```

Open <http://127.0.0.1:8080> and sign in with `ADMIN_USER` / `ADMIN_PASSWORD`. The port is bound
to loopback; `HTTP_PORT` / `HTTP_BIND` in `.env` change that — but put a TLS reverse proxy in front
before exposing it (see [`docs/DEPLOY.md`](docs/DEPLOY.md)).

| Image | Contents |
|-------|----------|
| `ghcr.io/asyncdargen/claude-proxy-service` | Kotlin service — management API, control API, Kotlin datapath |
| `ghcr.io/asyncdargen/claude-proxy-gateway` | Go data plane — Claude Code datapath + OpenAI/Anthropic routing |
| `ghcr.io/asyncdargen/claude-proxy-web` | nginx — the web UI and the router in front of the other two |
| `ghcr.io/asyncdargen/claude-proxy` | all-in-one: the three above in one container |

**Pin a version:** set `CLAUDE_PROXY_TAG=0.2.0` in `.env` (default `latest`; every commit is also
published as `sha-<short>`). **Update:** `docker compose pull && docker compose up -d`.

## Features

**Routing & limits**
- **Priority rotation** — accounts are used strictly one after another, by priority.
- **Per-account threshold** (e.g. `0.9`) with opt-in **fallback** past the threshold once the
  whole pool is saturated.
- **Live window tracking** from Anthropic's `anthropic-ratelimit-unified-{5h,7d}-utilization`
  response headers, plus a background prober that keeps window state fresh while idle.
- **Account plans** (Pro / Max / Ultra) weight pool headroom; verified Ultra accounts, which have
  no 5-hour window, are detected from the response headers and shown weekly-only.
- **API keys and foreign upstreams** — an API-key account can target any Anthropic-compatible base
  URL (another claude-proxy, a corporate gateway) with `x-api-key` or bearer auth, and present
  requests as Claude Code, the Agent SDK or a plain API call. Keys have no windows or thresholds;
  they are health-checked for free via `/v1/models`.
- **Per-account outbound proxy** — HTTP (CONNECT) or SOCKS5 with credentials, used for every call
  made for that account (inference, chat, probes, token refresh), never falling back to direct.
- **Transparent retry across accounts** on `429/401/5xx`; hard-limited accounts are parked until
  their window resets.
- **Mid-stream failover** — Anthropic can return `overloaded_error` *after* a `200`. If nothing
  client-visible has been written yet, the next account takes the request over on the same open
  stream, invisibly to the client.
- **Personal accounts** — a user can attach their own; they are tried before the shared pool (or
  after it, if the user flips the order), are excluded from global stats, and don't count against
  the shared daily limit.

**Accounts & auth**
- **Account types:** OAuth (access + refresh, auto-refreshed in the background), OAuth static
  (access only), API key (`x-api-key`).
- **Add accounts in the UI** by pasting a credential, or through the **Login with Claude** OAuth
  (PKCE) flow.
- **Secrets encrypted at rest** with AES-256-GCM under your `MASTER_KEY`.

**Multi-user**
- **Users, roles & granular permissions**, from `proxy.use` up to `admin`; account **groups**
  scope which accounts a user may route through.
- **Proxy tokens** (`cxp_…`) for Claude Code and **routing tokens** (`cxr_…`) for the OpenAI /
  Anthropic gateways — separate namespaces, stored as SHA-256, individually revocable and
  toggleable.
- **Per-user daily USD limits**, tracked separately for the proxy and the routing datapaths.
- A routing token can carry a **static system prompt**, injected ahead of anything the client
  sends.

**Observability**
- Per-model USD pricing (input / output / cache read / cache write) → per-request cost.
- Cost, token and request time series; per-token and per-user breakdowns; **window burn per day**
  (how much of each limit window a day actually consumed); live **active session** count.
- **MCP tool-call accounting** — `mcp__server__tool` invocations are parsed out of the response
  stream and counted per tool.
- Statistics are sliced on the **viewer's** timezone. The daily USD limits are enforced on UTC
  days, so the figures read against them are labelled UTC and carry their own 00:00 countdown.

## Architecture

One nginx — the **web** image, which also serves the SPA — fronts every component on a single origin:

| Public path            | Component                  | What it is |
|------------------------|----------------------------|------------|
| `/`                    | **web** (nginx)            | React SPA (admin UI + chat), static |
| `/api/…`               | **service** (Kotlin/Ktor)  | Management REST API |
| `/gateway/…`           | **gateway** (Go)           | The Claude Code datapath |
| `/routing/openai/…`    | **gateway** (same container) | OpenAI Chat Completions over your pool |
| `/routing/anthropic/…` | **gateway** (same container) | Anthropic Messages over your pool |

The Go gateways are a stateless data plane: they resolve each request against the service's
private `/internal/*` control API (which owns selection, crypto and bookkeeping), forward to
Anthropic, relay SSE, and report usage back. They never touch the database. The Kotlin service
also still contains a complete datapath, kept as an instant rollback target — setting
`DATAPATH_UPSTREAM=service:8787` on the web container reverts to it.

Layout: `frontend/` (React) · `service/` (Kotlin) · `gateway/` (Go — one module, one binary,
all three paths on one port) · `deploy/` (Dockerfiles, the web image's nginx config, quick-start
compose files) · `scripts/` (release, deploy, rollback, bootstrap) · `docs/`.

Depth: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Stack

Kotlin 2.2 / Ktor 3.2 / Exposed / JVM 21 · Go 1.23 (stdlib `net/http`, no framework) ·
PostgreSQL 16 (SQLite fallback for local runs) · React 18 + Vite + TypeScript (hand-rolled SVG
charts, no UI framework) · Docker Compose.

## Requirements

- Docker + Docker Compose — for the full stack.
- To run pieces natively: JDK 21+, Go 1.23+, Node 18+ with `pnpm`.

## First steps

Once it is up (see [Quick start](#quick-start-prebuilt-images)), sign in and add an account
(**Dashboard → Add account**, or *Login with Claude*), then create a token.

To run the stack from a checkout instead, the root `docker-compose.yml` (the production shape:
fixed container names, `./pgdata` bind mount) pulls the same images, or builds them locally:

```bash
cp .env.example .env      # edit the secrets as above
docker compose build      # optional — otherwise `up` pulls the published images
docker compose up -d
```

### Point Claude Code at it

Create a proxy token on the **Proxy Tokens** page, then:

```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8080/gateway
export ANTHROPIC_AUTH_TOKEN=cxp_...
claude
```

### Point any OpenAI / Anthropic client at it

Create a routing token on the **API Routing** page (needs the `routing.use` permission) and use it
as an ordinary API key:

```
# OpenAI-compatible clients
base_url = http://127.0.0.1:8080/routing/openai/v1
api_key  = cxr_...

# Anthropic SDK clients
base_url = http://127.0.0.1:8080/routing/anthropic
api_key  = cxr_...
```

## Development

```bash
# terminal 1 — Kotlin service (reads .env), http://127.0.0.1:8787
cd service && ./gradlew run
# terminal 2 — Vite dev server with HMR, http://localhost:5173
#   proxies /api /gateway /v1 /healthz to the service — no nginx needed
cd frontend && pnpm install && pnpm dev
```

The Go gateway runs standalone against a running service. One process serves all three surfaces
on one port — `/v1/…` is the datapath, `/routing/openai/…` and `/routing/anthropic/…` the routing
gateways:

```bash
cd gateway
SERVICE_URL=http://localhost:8787 INTERNAL_TOKEN=devtok go run .   # :9000
```

Tests:

```bash
cd service && ./gradlew test
cd gateway && go test ./... && go vet ./...
```

### Building and releasing images

All four images are defined in [`docker-bake.hcl`](docker-bake.hcl) (Dockerfiles in `deploy/`).
Builder stages run on the build host's platform — the jar and the SPA are platform-neutral and Go
cross-compiles — so a multi-arch build needs emulation only for the small runtime `RUN` steps.

```bash
docker buildx bake -f docker-bake.hcl --print                 # resolved definition
scripts/release-images.sh --load                              # native-arch build into the local docker
scripts/release-images.sh --load --platform linux/amd64 web   # one image, one platform
scripts/release-images.sh                                     # amd64+arm64, push :latest :<version> :sha-<short>
```

`release-images.sh` takes the version from `service/build.gradle.kts` (override with `VERSION=`),
the revision from git, and creates a `docker-container` buildx builder (`claude-proxy-builder`)
when it is missing; pushing needs `docker login ghcr.io`. `REGISTRY=ghcr.io/you` publishes under
another owner. In CI, pushing a `v*` tag runs [`.github/workflows/images.yml`](.github/workflows/images.yml),
which publishes `:latest`, `:<version>` and `:sha-<short>`; a manual run publishes `:edge` (or the
tag you give it).

## Deployment

A Compose stack on one host behind a TLS-terminating reverse proxy, running the published images
— the server builds nothing. Set strong `MASTER_KEY` / `SESSION_SECRET` / `INTERNAL_TOKEN`, and
`PUBLIC_DOMAIN`. `DEPLOY_HOST=root@YOUR_SERVER scripts/deploy.sh` snapshots the running images and
the database, copies `docker-compose.yml`, pulls and recreates service → gateway → web one at a
time; `scripts/rollback.sh` puts the snapshot back. Full runbook, safety rules (the deploy
directory holds the bind-mounted database — never `rsync --delete` into it) and a troubleshooting
table: [`docs/DEPLOY.md`](docs/DEPLOY.md).

## Configuration

Everything is environment variables, optionally via a local `.env` — see
[`.env.example`](.env.example) for the annotated list.

`MASTER_KEY` encrypts every stored account credential. **Losing it means losing every stored
account**, and rotating it invalidates them all. Back it up separately from the database.

### Anthropic outgoing proxy

Set `ANTHROPIC_PROXY_URL=http://proxy.example.com:3128` in the private `.env` and recreate
**both service and gateway** to route server-side Anthropic requests through an HTTP CONNECT
proxy. Optional URL-encoded username/password are supported. This covers inference (including
routing and Kotlin fallback/chat), OAuth token exchange/refresh/profile and limit probes.
HTTPS upstream certificates are still verified; TLS is tunneled, not decrypted by this proxy.

An empty setting keeps the existing direct route. Invalid configuration fails startup; proxy
connection/authentication errors do not fall back to direct. Global `HTTP_PROXY`, `HTTPS_PROXY`
and `NO_PROXY` do not control this dedicated route. Internal control calls keep their existing route. Browser sign-in, telemetry and app updates on developer machines
are outside this server setting. Known client IP/forwarding headers, cookies and client auth
are removed before the Go datapath sends the selected account credentials upstream.

Use a trusted proxy: an HTTP proxy observes destination hosts and carries its own Basic
authentication outside the upstream TLS tunnel. Protect its network connection separately
(e.g. a private network). This feature does not establish a VPN or a host-wide firewall rule.

### Single sign-on (OIDC)

Optional, off by default: `OIDC_ENABLED=true` plus issuer, client id/secret and redirect URI adds
a "Sign in with SSO" button to the panel (Authorization Code + PKCE, ID token validated by the
Nimbus SDK against the provider's JWKS). Identities link to local users by `(issuer, sub)`;
email linking, JIT provisioning (pending admin approval by default), group and email-domain gates
are opt-in. Admins can **offboard** a user (disable, end sessions, revoke every `cxp_`/`cxr_`
token) in one action — available with or without OIDC. API token authentication is unchanged. Setup and operational notes:
[`docs/OIDC.md`](docs/OIDC.md).

## Security notes

- Never expose `/internal/*` publicly — the bundled nginx config does not route it, and it is
  gated by the shared `INTERNAL_TOKEN`.
- The bootstrap admin is created only while the users table is empty; change its password
  immediately after the first login.
- Tokens are stored hashed (SHA-256) and shown in full exactly once, at creation.
- Anthropic subscription accounts need the Claude Code identity headers and system prompt to be
  accepted upstream; the gateways inject them. Read [`CLAUDE.md`](CLAUDE.md) before touching that
  path — parts of it (the SSE relay in particular) are timing-sensitive and load-bearing.

## Contributing

Issues and pull requests are welcome. Please run both test suites before opening a PR, and keep
[`CLAUDE.md`](CLAUDE.md) — the working notes for humans and AI agents alike — current when you
change architecture or deploy steps.

## License

[MIT](LICENSE).
