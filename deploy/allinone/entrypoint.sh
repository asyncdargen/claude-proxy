#!/usr/bin/env bash
# All-in-one entrypoint: renders the nginx config, starts service, gateway and nginx, and exits
# as soon as any of them dies — non-zero, so the container's restart policy brings the whole
# set back instead of leaving a half-alive container that passes no requests.
set -euo pipefail

log() { echo "[claude-proxy] $*" >&2; }

# The control API between service and gateway never leaves the container, so a quick start
# needs no extra secret: generate one per start unless the operator pinned it.
if [ -z "${INTERNAL_TOKEN:-}" ]; then
    INTERNAL_TOKEN="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    log "INTERNAL_TOKEN not set — generated a random one for this start"
fi
export INTERNAL_TOKEN

# Resolver: only needed if an upstream is overridden with a host name; default to the
# container's own DNS server.
if [ -z "${RESOLVER:-}" ]; then
    RESOLVER="$(awk '/^nameserver/ { print $2; exit }' /etc/resolv.conf 2>/dev/null || true)"
    RESOLVER="${RESOLVER:-127.0.0.11}"
    export RESOLVER
fi
# shellcheck source=/dev/null
. /usr/local/lib/claude-proxy-upstreams.envsh
envsubst '${SERVICE_UPSTREAM} ${GATEWAY_UPSTREAM} ${DATAPATH_UPSTREAM} ${RESOLVER}' \
    < /etc/nginx/templates/default.conf.template > /etc/nginx/conf.d/default.conf
nginx -t -q

# Both binaries read PORT, so each gets its own; the service binds loopback only — nginx is
# the single door into the container.
env BIND_HOST=127.0.0.1 PORT=8787 java ${JAVA_OPTS:-} -jar /app/app.jar &
service_pid=$!
env PORT=9000 SERVICE_URL=http://127.0.0.1:8787 /usr/local/bin/gateway &
gateway_pid=$!
nginx -g 'daemon off;' &
nginx_pid=$!
log "started service (pid $service_pid), gateway (pid $gateway_pid), nginx (pid $nginx_pid)"

stopping=0
shutdown() {
    stopping=1
    kill -TERM "$service_pid" "$gateway_pid" "$nginx_pid" 2>/dev/null || true
}
trap shutdown TERM INT

status=0
wait -n || status=$?
if [ "$stopping" -eq 0 ]; then
    log "a process exited (status $status) — stopping the rest"
    shutdown
    stopping=0
fi
wait || true
if [ "$stopping" -eq 1 ]; then
    exit 0
fi
# A child that exits 0 on its own is still a failure here: nothing is supposed to stop.
[ "$status" -eq 0 ] && status=1
exit "$status"
