#!/usr/bin/env bash
# Roll the app containers back to the images snapshotted by scripts/deploy.sh. No pull, no build.
#
#   DEPLOY_HOST=root@YOUR_SERVER scripts/rollback.sh [<ts>] [--dir /opt/claude-proxy] [--only service]
#
#   <ts>        snapshot to restore (default: LAST_PREDEPLOY_TS written by the last deploy)
#   --only X    restore just one of service | gateway | web (repeatable)
#   --restore-compose  also put back the docker-compose.yml saved with that snapshot
#
# Each :rollback-<ts> image is re-tagged as the tag compose runs (CLAUDE_PROXY_TAG from the server
# .env, else latest) and the container is recreated with --no-deps. A later `docker compose pull`
# brings the published images back. The database is not touched — restore a dump from the backup
# dir by hand if a migration has to be undone.
set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:-}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/claude-proxy}"
BACKUP_DIR="${BACKUP_DIR:-}"
ts=""
restore_compose=0
only=()

while [ $# -gt 0 ]; do
    case "$1" in
        --dir) DEPLOY_DIR="$2"; shift ;;
        --backup-dir) BACKUP_DIR="$2"; shift ;;
        --host) DEPLOY_HOST="$2"; shift ;;
        --only) only+=("$2"); shift ;;
        --restore-compose) restore_compose=1 ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 2 ;;
        *) ts="$1" ;;
    esac
    shift
done

if [ -z "$DEPLOY_HOST" ]; then
    echo "DEPLOY_HOST is required (e.g. DEPLOY_HOST=root@YOUR_SERVER $0)" >&2
    exit 2
fi
BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
[ ${#only[@]} -eq 0 ] && only=(service gateway web)

# ssh joins its arguments into one command line, so an empty one would vanish: quote each.
remote_args() { printf '%q ' "$@"; }
ssh "$DEPLOY_HOST" "bash -s -- $(remote_args "$DEPLOY_DIR" "$BACKUP_DIR" "$restore_compose" "$ts" "${only[@]}")" <<'REMOTE'
set -euo pipefail
deploy_dir="$1"; backup_dir="$2"; restore_compose="$3"; ts="$4"; shift 4
cd "$deploy_dir"
if [ -z "$ts" ]; then
    ts="$(cat "$backup_dir/LAST_PREDEPLOY_TS" 2>/dev/null || true)"
    [ -n "$ts" ] || { echo "!! no snapshot given and no $backup_dir/LAST_PREDEPLOY_TS" >&2; exit 1; }
fi
if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
tag="$(sed -n 's/^CLAUDE_PROXY_TAG=//p' .env | tail -n1)"
tag="${tag:-latest}"
echo "==> rolling back to snapshot $ts (as :$tag)"

if [ "$restore_compose" = 1 ]; then
    [ -f "$backup_dir/docker-compose.yml.$ts" ] || { echo "!! no saved compose file for $ts" >&2; exit 1; }
    cp docker-compose.yml "$backup_dir/docker-compose.yml.before-rollback-$ts" 2>/dev/null || true
    cp "$backup_dir/docker-compose.yml.$ts" docker-compose.yml
    echo "    restored docker-compose.yml from the snapshot"
fi

for svc in "$@"; do
    case "$svc" in
        service) repo=ghcr.io/asyncdargen/claude-proxy-service; container=claude-proxy-service ;;
        gateway) repo=ghcr.io/asyncdargen/claude-proxy-gateway; container=claude-proxy-gateway ;;
        web) repo=ghcr.io/asyncdargen/claude-proxy-web; container=claude-proxy-nginx ;;
        *) echo "!! unknown service $svc" >&2; exit 2 ;;
    esac
    if ! docker image inspect "$repo:rollback-$ts" >/dev/null 2>&1; then
        echo "    $svc: no $repo:rollback-$ts — skipped"
        continue
    fi
    docker tag "$repo:rollback-$ts" "$repo:$tag"
    echo "==> $svc ← $repo:rollback-$ts"
    "${compose[@]}" up -d --no-deps --force-recreate "$svc"
    deadline=$((SECONDS + 180))
    until [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null)" = healthy ]; do
        [ "$SECONDS" -lt "$deadline" ] || { echo "!! $container not healthy after 180s" >&2; docker logs --tail 30 "$container" >&2; exit 1; }
        sleep 3
    done
    echo "    $container healthy"
done

code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/healthz || true)"
echo "==> /healthz through the router → $code"
[ "$code" = 200 ]
REMOTE
