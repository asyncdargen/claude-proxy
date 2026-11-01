#!/usr/bin/env bash
# Pull-based production deploy: the server builds nothing, it pulls the published images.
#
#   DEPLOY_HOST=root@YOUR_SERVER scripts/deploy.sh [--tag 0.2.0] [--dir /opt/claude-proxy]
#
#   DEPLOY_HOST  ssh target (required; also the first positional argument)
#   DEPLOY_DIR   deploy directory on the server (default /opt/claude-proxy)
#   TAG          image tag for this run (default: CLAUDE_PROXY_TAG from the server .env, else latest)
#   BACKUP_DIR   where dumps + rollback markers go (default $DEPLOY_DIR/backups)
#
# Steps: snapshot (tag running images :rollback-<ts>, pg_dump, keep the old compose file) →
# copy docker-compose.yml → pull → recreate service, gateway, web one at a time, each waited on
# until healthy → drop the retired `front` container → curl /healthz through the router.
# Roll back with scripts/rollback.sh.
#
# SAFETY: the deploy directory holds runtime state that is not in the repo (pgdata/, .env, data/).
# This script copies exactly one file into it, docker-compose.yml. Never rsync --delete there.
# The server must already be logged in to ghcr.io if the packages are private.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEPLOY_HOST="${DEPLOY_HOST:-}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/claude-proxy}"
TAG="${TAG:-}"
BACKUP_DIR="${BACKUP_DIR:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG="$2"; shift ;;
        --dir) DEPLOY_DIR="$2"; shift ;;
        --backup-dir) BACKUP_DIR="$2"; shift ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 2 ;;
        *) DEPLOY_HOST="$1" ;;
    esac
    shift
done

if [ -z "$DEPLOY_HOST" ]; then
    echo "DEPLOY_HOST is required (e.g. DEPLOY_HOST=root@YOUR_SERVER $0)" >&2
    exit 2
fi
BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
ts="$(date -u +%Y%m%d%H%M%S)"
# ssh joins its arguments into one command line, so an empty one would vanish: quote each.
remote_args() { printf '%q ' "$@"; }

echo "==> deploy to $DEPLOY_HOST:$DEPLOY_DIR (tag ${TAG:-<server default>}, snapshot $ts)"

# ---- (a) snapshot on the server: rollback image tags, DB dump, the old compose file ----
ssh "$DEPLOY_HOST" "bash -s -- $(remote_args "$DEPLOY_DIR" "$BACKUP_DIR" "$ts")" <<'REMOTE'
set -euo pipefail
deploy_dir="$1"; backup_dir="$2"; ts="$3"
cd "$deploy_dir"
[ -f .env ] || { echo "!! $deploy_dir/.env is missing — refusing to deploy" >&2; exit 1; }
mkdir -p "$backup_dir"

# container → image repository it is snapshotted under
snapshot() {
    local container="$1" repo="$2" image
    image="$(docker inspect -f '{{.Image}}' "$container" 2>/dev/null)" || {
        echo "    $container not running — nothing to snapshot"; return 0; }
    docker tag "$image" "$repo:rollback-$ts"
    echo "    $container → $repo:rollback-$ts"
}
echo "==> snapshotting running images as :rollback-$ts"
snapshot claude-proxy-service ghcr.io/asyncdargen/claude-proxy-service
snapshot claude-proxy-gateway ghcr.io/asyncdargen/claude-proxy-gateway
# Before the move to the web image, claude-proxy-nginx was the bare router (SPA in a separate
# `front` container): that image can't stand in for web, so it is not offered as a rollback.
svc="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' claude-proxy-nginx 2>/dev/null || true)"
if [ "$svc" = web ]; then
    snapshot claude-proxy-nginx ghcr.io/asyncdargen/claude-proxy-web
elif [ -n "$svc" ]; then
    echo "    claude-proxy-nginx is the pre-web router (service '$svc') — not snapshotted;"
    echo "    to undo this first migration restore $backup_dir/docker-compose.yml.$ts and rebuild"
fi

if docker inspect claude-proxy-db >/dev/null 2>&1; then
    dump="$backup_dir/pg-$ts.sql.gz"
    echo "==> pg_dump → $dump"
    docker exec claude-proxy-db pg_dump -U claudeproxy claudeproxy | gzip > "$dump"
    [ -s "$dump" ] || { echo "!! empty dump — aborting" >&2; exit 1; }
fi
[ -f docker-compose.yml ] && cp docker-compose.yml "$backup_dir/docker-compose.yml.$ts"
echo "$ts" > "$backup_dir/LAST_PREDEPLOY_TS"
REMOTE

# ---- (b) the one file the server needs from the repo ----
echo "==> copying docker-compose.yml"
scp -q "$repo_root/docker-compose.yml" "$DEPLOY_HOST:$DEPLOY_DIR/docker-compose.yml"

# ---- (c..f) pull, recreate one by one, verify ----
ssh "$DEPLOY_HOST" "bash -s -- $(remote_args "$DEPLOY_DIR" "$TAG")" <<'REMOTE'
set -euo pipefail
deploy_dir="$1"; tag="$2"
cd "$deploy_dir"
[ -n "$tag" ] && export CLAUDE_PROXY_TAG="$tag"
if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi

wait_healthy() {
    local container="$1" deadline=$((SECONDS + 180)) state
    printf '    waiting for %s ' "$container"
    while :; do
        state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo missing)"
        case "$state" in
            healthy) echo "healthy"; return 0 ;;
            unhealthy|exited|dead) echo "$state"; docker logs --tail 30 "$container" >&2 || true; return 1 ;;
        esac
        if [ "$SECONDS" -ge "$deadline" ]; then
            echo "timeout ($state)"; docker logs --tail 30 "$container" >&2 || true; return 1
        fi
        printf '.'; sleep 3
    done
}

echo "==> pulling images (tag ${CLAUDE_PROXY_TAG:-from .env or latest})"
"${compose[@]}" pull service gateway web

echo "==> recreating service"
"${compose[@]}" up -d --no-deps --force-recreate service
wait_healthy claude-proxy-service

echo "==> recreating gateway"
"${compose[@]}" up -d --no-deps --force-recreate gateway
wait_healthy claude-proxy-gateway

# The old router ran as compose service `nginx` under the same container name; compose won't
# adopt it for `web`, so it goes first (a few seconds of edge downtime, once).
svc="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' claude-proxy-nginx 2>/dev/null || true)"
if [ -n "$svc" ] && [ "$svc" != web ]; then
    echo "==> removing the pre-web router container (service '$svc')"
    docker rm -f claude-proxy-nginx >/dev/null
fi
echo "==> recreating web"
"${compose[@]}" up -d --no-deps --force-recreate web
wait_healthy claude-proxy-nginx

if docker inspect claude-proxy-front >/dev/null 2>&1; then
    echo "==> removing the retired claude-proxy-front container"
    docker rm -f claude-proxy-front >/dev/null
fi

echo "==> health through the router"
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/healthz || true)"
echo "    /healthz → $code"
[ "$code" = 200 ] || { echo "!! router health check failed" >&2; exit 1; }
"${compose[@]}" ps
REMOTE

echo "==> done. Roll back with: DEPLOY_HOST=$DEPLOY_HOST scripts/rollback.sh $ts"
