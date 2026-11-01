# Deployment & operations

The recommended production shape is a Docker Compose stack on a single host, fronted by a
TLS-terminating reverse proxy. Throughout this document:

| Placeholder | Meaning |
|-------------|---------|
| `root@YOUR_SERVER` | your SSH target |
| `/opt/claude-proxy` | the deploy directory on that host |
| `proxy.example.com` | the public domain you serve the proxy on |

```
Claude Code / browser
   → (optional CDN, e.g. Cloudflare — TLS, proxied DNS)
     → host nginx (:443, proxy_pass http://127.0.0.1:8080)
       → claude-proxy-nginx, the `web` image (host 127.0.0.1:8080) — serves the SPA itself and
         routes /api, /gateway, /v1, /routing/*
         → claude-proxy-service (service:8787)            # /api + /internal control API + Kotlin datapath (rollback)
         → claude-proxy-gateway (gateway:9000)            # /v1 datapath + /routing/{openai,anthropic}
           → the service's /internal/resolve + /internal/usage (crypto, pool, usage)
             → Postgres claude-proxy-db (./pgdata)
```

The datapath base URL clients use is `https://proxy.example.com/gateway` (Claude Code appends
`/v1/…`). The routing gateways are at `https://proxy.example.com/routing/openai/v1` and
`https://proxy.example.com/routing/anthropic`.

A TLS-terminating host proxy is not strictly required — the compose router publishes
`127.0.0.1:8080` and any reverse proxy (nginx, Caddy, Traefik) can front it. Keep whatever you
choose SSE-safe: buffering **off**, long read timeouts.

## Images

Everything runs from prebuilt multi-arch (`linux/amd64`, `linux/arm64`) images on GHCR — the
server builds nothing:

| Image | Dockerfile | What it is |
|-------|------------|------------|
| `ghcr.io/asyncdargen/claude-proxy-service` | `deploy/Dockerfile.service` | Kotlin service (API + control API + Kotlin datapath) |
| `ghcr.io/asyncdargen/claude-proxy-gateway` | `deploy/Dockerfile.gateway` | Go data plane (`/v1` + `/routing/*`) |
| `ghcr.io/asyncdargen/claude-proxy-web` | `deploy/Dockerfile.web` | nginx: the SPA **and** the edge router (config in `deploy/web/`) |
| `ghcr.io/asyncdargen/claude-proxy` | `deploy/Dockerfile.allinone` | all three in one container — quick starts only |

Tags: `latest`, `<version>` (e.g. `0.2.0`, from a `v0.2.0` git tag), `sha-<short>`. They are built
by `.github/workflows/images.yml` on a `v*` tag push, or by hand with `scripts/release-images.sh`
(`docker buildx bake` over `docker-bake.hcl`). If the packages are private, the server needs a
one-time `docker login ghcr.io` with a token that has `read:packages`.

## Compose stack (`docker-compose.yml`)

- **postgres** — `postgres:16-alpine`, container `claude-proxy-db`, data bind-mounted at
  `./pgdata`. Initialized with `POSTGRES_PASSWORD=${DATABASE_PASSWORD}`.
- **service** — `claude-proxy-service` image, container `claude-proxy-service`. **No host port**
  — reachable only as `service:8787` on the compose network. `env_file: .env` plus an
  `environment:` block pinning `BIND_HOST=0.0.0.0` and passing through `PUBLIC_DOMAIN`,
  `DATABASE_*` and `INTERNAL_TOKEN`. SQLite dir bind-mounted at `./data`. `curl /healthz`
  healthcheck.
- **gateway** — `claude-proxy-gateway` image, container `claude-proxy-gateway`, reachable as
  `gateway:9000`. Calls the service's `/internal/*` control API (shared `INTERNAL_TOKEN`), forwards
  to Anthropic. `depends_on: service (healthy)`; `wget /healthz` healthcheck.
  The API routing gateways live in this **same** container and port, mounted at `/routing/openai/`
  and `/routing/anthropic/`; the router forwards those prefixes verbatim and the gateway strips them.
- **web** — `claude-proxy-web` image, container **`claude-proxy-nginx`** (the name the old router
  had). Serves the SPA and routes everything else; publishes `127.0.0.1:8080:8080`.
  `depends_on: service (healthy)` + `gateway (started)`. The nginx config is baked into the image
  (`deploy/web/default.conf.template`); upstreams come from env: `SERVICE_UPSTREAM`
  (`service:8787`), `GATEWAY_UPSTREAM` (`gateway:9000`, `/routing/*`), `DATAPATH_UPSTREAM`
  (`/gateway/`, `/v1/`; defaults to the gateway). nginx re-resolves them through Docker DNS every
  10s, so recreating a backend no longer needs an `nginx -s reload`. `proxy_stream.conf` is the
  SSE-safe include.

Image tags come from `CLAUDE_PROXY_TAG` (default `latest`); pin a release by setting it in the
server `.env` (compose reads that file for variable substitution too). The `build:` sections stay
so a local `docker compose build` still works — production never uses them.

> **`INTERNAL_TOKEN`** must be present in the server `.env` (a long random secret, e.g.
> `openssl rand -hex 32`), shared by `service` and the gateway. Without it the control API
> stays `401` and the gateways can't resolve. nginx **never** routes `/internal` publicly.

### Server `.env`

The deploy directory holds a server-managed `.env` (never synced from your working copy). At
minimum:

```dotenv
MASTER_KEY=<32+ random chars>          # encrypts account credentials at rest — losing it loses them
SESSION_SECRET=<random>
ADMIN_USER=admin
ADMIN_PASSWORD=<strong>                # bootstrap admin, only used while the users table is empty
BIND_HOST=0.0.0.0
PUBLIC_DOMAIN=proxy.example.com        # token base URL + CORS allowHost
DATABASE_PASSWORD=<random>             # must match what ./pgdata was initialized with
INTERNAL_TOKEN=<openssl rand -hex 32>  # shared with the gateway
# CLAUDE_PROXY_TAG=0.2.0               # optional: pin the image release (default latest)
```

## ⚠️ Critical safety rules

The deploy directory contains **runtime state that is not in the repo**. Treat it carefully:

1. **NEVER `rsync --delete` into `/opt/claude-proxy/`.** It will delete `pgdata/` (the whole
   database), `.env` (secrets), and `data/`. This has already caused one total DB loss in
   practice.
2. **`.env` is server-managed.** Never overwrite it from the local repo — the local one differs
   (e.g. `BIND_HOST`) and lacks `DATABASE_PASSWORD`. Exclude it from every sync.
3. **`DATABASE_PASSWORD` must stay in the server `.env`** and match the value the `pgdata`
   volume was initialized with. If it's missing, compose defaults to `claudeproxy`, which fails
   auth against the existing volume and the app crash-loops on
   `password authentication failed for user "claudeproxy"`.
4. **Take a dump before risky work:**
   `docker exec claude-proxy-db pg_dump -U claudeproxy claudeproxy > backups/pg-$(date +%s).sql`.
   `scripts/deploy.sh` does this on every deploy (`backups/pg-<ts>.sql.gz`).
5. **The only repo file the server needs is `docker-compose.yml`.** Copy that one file
   (`scp`); there is no source tree to sync any more.

## Deploy (pull-based)

Publish images first (tag push → CI, or `scripts/release-images.sh`), then from the repo root:

```bash
DEPLOY_HOST=root@YOUR_SERVER scripts/deploy.sh                 # tag from the server .env, else latest
DEPLOY_HOST=root@YOUR_SERVER scripts/deploy.sh --tag 0.2.0     # this run uses :0.2.0
DEPLOY_HOST=root@YOUR_SERVER DEPLOY_DIR=/srv/claude-proxy scripts/deploy.sh
```

What it does, in order:

1. **Snapshot** on the server: re-tags the images the running `claude-proxy-service`,
   `-gateway` and `-nginx` containers use as `:rollback-<ts>`, `pg_dump`s into
   `$DEPLOY_DIR/backups/pg-<ts>.sql.gz` (aborts on an empty dump), saves the current
   `docker-compose.yml` as `backups/docker-compose.yml.<ts>` and writes `<ts>` to
   `backups/LAST_PREDEPLOY_TS`. Refuses to run if `$DEPLOY_DIR/.env` is missing.
2. **Copies `docker-compose.yml`** with `scp` — nothing else; `.env`, `pgdata/`, `data/` are never
   touched.
3. **`docker compose pull service gateway web`**.
4. **Recreates one at a time** (`up -d --no-deps --force-recreate`), waiting up to 180s for each to
   report healthy: service → gateway → web. postgres is never recreated.
5. Removes the retired `claude-proxy-front` container, and — once, on the first deploy after the
   split router — the old `nginx`-service container that held the `claude-proxy-nginx` name.
6. Checks `http://127.0.0.1:8080/healthz` through the router.

It works with Compose v2 (`docker compose`) and falls back to `docker-compose` v1 (the file keeps
`version: "2.4"` for it). When the `/internal/*` contract changes, the service still goes first —
the script's order already does that.

**Rollback** to the images of the last deploy's snapshot (no pull, no build):

```bash
DEPLOY_HOST=root@YOUR_SERVER scripts/rollback.sh                    # snapshot from LAST_PREDEPLOY_TS
DEPLOY_HOST=root@YOUR_SERVER scripts/rollback.sh 20260801120000 --only service
DEPLOY_HOST=root@YOUR_SERVER scripts/rollback.sh --restore-compose  # also the compose file of then
```

It re-tags `:rollback-<ts>` as the tag compose runs and recreates the containers. The database is
not rolled back — restore a dump by hand if a migration must be undone. The first deploy after the
move to the `web` image can't roll `web` back this way (the old router image needed the separate
`front` container): restore `backups/docker-compose.yml.<ts>` and the old images/source instead.

Verify:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://proxy.example.com/healthz  # -> 200
docker logs --tail 5 claude-proxy-service | grep Responding                 # -> Responding at http://0.0.0.0:8787
docker logs --tail 5 claude-proxy-gateway | grep 'gateway on'               # -> gateway on :9000 -> https://api.anthropic.com
docker inspect -f '{{.Config.Image}}' claude-proxy-service claude-proxy-gateway claude-proxy-nginx
```

### Verifying a gateway before it is public

The Go datapath can be rolled out while the router still points `/gateway`,`/v1` at the Kotlin
datapath (`DATAPATH_UPSTREAM=service:8787`) — a broken gateway then has **no client impact**. Test it from inside the compose
network:

```bash
ssh root@YOUR_SERVER 'docker exec claude-proxy-gateway wget -qO- http://127.0.0.1:9000/healthz'
ssh root@YOUR_SERVER 'docker run --rm --network claude-proxy_default curlimages/curl -s \
  -o /dev/null -w "%{http_code}\n" -X POST http://gateway:9000/v1/messages \
  -H "Authorization: Bearer <a real cxp_ token>" -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":16,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"'
# -> 200; `docker logs claude-proxy-gateway` shows the SSE relay draining + a usage report.
```

**Datapath rollback:** add `DATAPATH_UPSTREAM=service:8787` to the server `.env` and recreate
the router: `docker compose up -d --no-deps --force-recreate web`. `/gateway/` (prefix stripped)
and `/v1/` then go to the Kotlin datapath, which never stopped running; `/routing/*` stays on the Go
gateway — the service has no routing endpoints, so pointing `GATEWAY_UPSTREAM` at it would turn
them into 404s. Remove the line and recreate `web` again to return to the gateway.

## Admin / access

- The bootstrap admin is created only when the users table is empty; credentials come from
  `ADMIN_USER` / `ADMIN_PASSWORD` in the server `.env`. Change the password after first login.
- Reach Postgres directly: `docker exec -it claude-proxy-db psql -U claudeproxy -d claudeproxy`.
- Point Claude Code at the proxy: create a token in the UI (**Proxy Tokens**), then
  `export ANTHROPIC_BASE_URL=https://proxy.example.com/gateway` and
  `export ANTHROPIC_AUTH_TOKEN=<proxy-token>` (the Tokens page shows the exact URL).

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `502` + nginx `upstream prematurely closed connection while reading response header`, client retries forever | SSE relay didn't flush the head early / buffered the stream during a long Opus "thinking" pause | the relay must flush the head immediately + stream incrementally + inject `: keep-alive` during silence (already handled — don't regress) |
| App crash-loops: `password authentication failed for user "claudeproxy"` | `DATABASE_PASSWORD` missing from the server `.env` after an `.env` overwrite | restore `DATABASE_PASSWORD` to the value `pgdata` was initialized with |
| `healthz` unreachable on host / nginx 502 though the container is "running" | app bound to container-loopback (`BIND_HOST=127.0.0.1`) | ensure compose `environment: BIND_HOST: "0.0.0.0"` |
| `/api/*` returns an empty `403` (login fails, `Vary: Origin`) | Ktor CORS rejects the SPA origin because `PUBLIC_DOMAIN` is unset | set `PUBLIC_DOMAIN` in the server `.env` |
| `502` where the request never reaches the handler (no `IN POST` log) | Netty decoder rejecting large request headers | `Application.kt` raises `maxHeaderSize` / `maxInitialLineLength` / `maxChunkSize` — keep them |
| `502`/`504` for a few seconds right after `--force-recreate` of a backend | the router caches upstream DNS for 10s (`resolver … valid=10s`) | wait; it re-resolves on its own (before the `web` image it needed `nginx -s reload`) |
| Empty DB / "Bootstrapped admin" on a DB that had data | `pgdata` was deleted (e.g. `rsync --delete`) and Postgres re-initialized | restore from a `pg_dump` backup |

## nginx notes

**Two nginx layers.**

- **Host nginx** — terminates TLS, optionally behind a CDN. It only needs to `proxy_pass` to
  `http://127.0.0.1:8080` (the compose router). Keep SSE-safe settings as defence in depth
  (`proxy_buffering off`, `proxy_read_timeout 3600s`, `large_client_header_buffers`,
  `client_max_body_size`).
- **Compose nginx** (`web` image, `deploy/web/`) — the path router + SPA server on `:8080`. Its datapath
  locations (`/gateway/`, `/v1/`, `/routing/*`) pull in `proxy_stream.conf`, which disables
  buffering and sets long read timeouts (the load-bearing SSE fix). Do not weaken it.
