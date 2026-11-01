#!/usr/bin/env bash
# Build every claude-proxy image (docker-bake.hcl) and push it to GHCR, multi-arch.
#
#   scripts/release-images.sh                          # push :latest, :<version>, :sha-<short>
#   scripts/release-images.sh --load                   # local build of the native arch, no push
#   scripts/release-images.sh --load --platform linux/arm64 web
#   scripts/release-images.sh --print                  # show the resolved bake definition
#
# Positional args are bake targets (service, gateway, web, allinone; default: all).
# Environment: VERSION (default: service/build.gradle.kts), REVISION (default: git HEAD),
# TAG (default: latest), REGISTRY (default: ghcr.io/asyncdargen), PLATFORMS,
# BUILDER (default: claude-proxy-builder, a docker-container builder created on demand —
# the plain docker driver can't push a multi-arch manifest).
# Pushing needs `docker login ghcr.io` with a token that has write:packages.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mode=push
platforms="${PLATFORMS:-}"
targets=()
while [ $# -gt 0 ]; do
    case "$1" in
        --load) mode=load ;;
        --print) mode=print ;;
        --push) mode=push ;;
        --platform) platforms="$2"; shift ;;
        --platform=*) platforms="${1#--platform=}" ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 2 ;;
        *) targets+=("$1") ;;
    esac
    shift
done

if [ -z "${VERSION:-}" ]; then
    VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' service/build.gradle.kts | head -n1)"
fi
if [ -z "${REVISION:-}" ]; then
    REVISION="$(git rev-parse HEAD 2>/dev/null || true)"
fi
if [ "$mode" = load ] && [ -z "$platforms" ]; then
    # --load can only take one platform: default to this machine's.
    case "$(uname -m)" in
        arm64|aarch64) platforms=linux/arm64 ;;
        *) platforms=linux/amd64 ;;
    esac
fi
export VERSION REVISION
[ -n "$platforms" ] && export PLATFORMS="$platforms"

if [ -n "$(git status --porcelain 2>/dev/null)" ] && [ "$mode" = push ]; then
    echo "warning: the working tree has uncommitted changes — they go into the images," >&2
    echo "         but the sha-${REVISION:0:7} tag will name the last commit." >&2
fi

builder="${BUILDER:-claude-proxy-builder}"
if [ "$mode" != print ] && ! docker buildx inspect "$builder" >/dev/null 2>&1; then
    echo "==> creating buildx builder $builder (docker-container driver)"
    docker buildx create --name "$builder" --driver docker-container --bootstrap >/dev/null
fi

echo "==> version=${VERSION:-<none>} revision=${REVISION:0:12} tag=${TAG:-latest}" \
     "platforms=${PLATFORMS:-linux/amd64,linux/arm64} mode=$mode targets=${targets[*]:-all}"

args=(-f docker-bake.hcl)
case "$mode" in
    push) args+=(--builder "$builder" --push) ;;
    load) args+=(--builder "$builder" --load) ;;
    print) args+=(--print) ;;
esac
exec docker buildx bake "${args[@]}" ${targets[@]+"${targets[@]}"}
