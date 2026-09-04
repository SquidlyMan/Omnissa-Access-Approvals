#!/bin/sh
# Omnissa Access Approvals — host-side updater (#83, part 3).
#
# Applies a deployment the administrator approved in the console. The
# application only ever writes a one-line file naming a version; this script
# is the only thing that touches Docker, and it runs on the host, where that
# privilege already lives — the container is never handed the socket.
#
# Triggered by omnissa-approvals-update.path when the intent file appears, or
# run by hand:  sudo sh update.sh
#
# What it does, in order, and why the order matters:
#   1. Find the compose file that actually owns the container — from the
#      container's own labels, never a hardcoded path. CasaOS adopts the app
#      into /var/lib/casaos/apps/<random-id>/ and a re-import changes the id.
#   2. Read and validate the requested version. Shape first (N.N.N), then
#      existence: ask the registry for the manifest, because 1.99.0 passes
#      the regex and would otherwise leave a rewritten compose and a stopped
#      container.
#   3. Record the current pin, so a rollback is exact.
#   4. Rewrite the pin, pull, recreate.
#   5. Verify — and NOT with the health endpoint. /actuator/health is UP on
#      any version, so it cannot tell a successful upgrade from a pull that
#      silently did nothing. Two checks, both required: the running image's
#      digest equals the registry's digest for the target, and /actuator/info
#      reports the target version.
#   6. On any failure, restore the recorded pin and recreate. Nothing is left
#      half-done.
#   7. Write the outcome next to the intent file for the console to show, then
#      consume the intent file. A deploy that changed nothing is a FAILURE.

set -u

APP=omnissa-approvals
REPO="${OMNISSA_UPDATE_REGISTRY_REPO:-squidlyman/omnissa-access-approvals}"
IMAGE="ghcr.io/$REPO"
FLOOR_MAJOR=1; FLOOR_MINOR=19; FLOOR_PATCH=5

log() { printf '%s update: %s\n' "$(date -u +%FT%TZ)" "$*"; }

# ZimaOS: / is read-only, so nothing docker or git does may touch $HOME.
export DOCKER_CONFIG=/media/ZIMARAID/$APP/docker-config
export HOME=/media/ZIMARAID/$APP

# The control directory: the HOST side of the container's /app/control mount.
# The unit passes it in (deploy.sh read it from the container at install time)
# so that this script can still CONSUME a request when the container is not
# running — a path unit re-fires for as long as the file exists, and a script
# that bailed out before removing it would be started again every few ms.
CONTROL="${OMNISSA_CONTROL_DIR:-}"
[ -n "$CONTROL" ] || CONTROL=$(docker inspect "$APP" --format '{{range .Mounts}}{{if eq .Destination "/app/control"}}{{.Source}}{{end}}{{end}}' 2>/dev/null || :)
[ -n "$CONTROL" ] && [ -d "$CONTROL" ] || { log "no control directory: set OMNISSA_CONTROL_DIR in the unit, or add the /app/control mount and recreate"; exit 1; }
INTENT="$CONTROL/update-requested"
RESULT="$CONTROL/update-result"

[ -f "$INTENT" ] || { log "nothing requested ($INTENT absent)"; exit 0; }

# Every exit after this point goes through result(): the verdict is written
# for the console and the request is consumed, whatever happened.
result() { # outcome target reason [digest] [version]
    {
        printf 'outcome=%s\n' "$1"
        printf 'target=%s\n' "$2"
        printf 'reason=%s\n' "$3"
        printf 'digest=%s\n' "${4:-}"
        printf 'version=%s\n' "${5:-}"
        printf 'at=%s\n' "$(date -u +%FT%TZ)"
    } > "$RESULT.tmp" && mv -f "$RESULT.tmp" "$RESULT"
    rm -f "$INTENT"
    log "$1: $2 — $3"
}
TARGET=$(head -n1 "$INTENT" | tr -d '[:space:]')

# ---- 1. the compose file that owns the container -----------------------------
COMPOSE=$(docker inspect "$APP" --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' 2>/dev/null || :)
PROJECT=$(docker inspect "$APP" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || :)
[ -n "$COMPOSE" ] && [ -f "$COMPOSE" ] && [ -n "$PROJECT" ] || { result failed "$TARGET" "cannot resolve the compose file from the container's labels — is $APP running?"; exit 1; }

# Health is checked over the LAN address, the way deploy.sh does: the LAN-only
# firewall rule in DOCKER-USER can drop a loopback probe that hairpins through
# the bridge, and a probe that cannot connect would look like a failed deploy.
LAN_IP="${LAN_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}' | head -1)}"
HEALTH_URL="${OMNISSA_UPDATE_HEALTH_URL:-http://${LAN_IP:-127.0.0.1}:8081}"

# ---- 2. the request, validated ----------------------------------------------------
case "$TARGET" in
    *[!0-9.]*|"") result refused "$TARGET" "not a release version (expected N.N.N)"; exit 1 ;;
esac
echo "$TARGET" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { result refused "$TARGET" "not a release version (expected N.N.N)"; exit 1; }

# The app already enforces the floor with a typed override; this is the host
# refusing to be driven below it by anything other than the app's own file —
# a file dropped in by hand does not get the override.
set -- $(echo "$TARGET" | tr '.' ' ')
if [ "$1" -lt $FLOOR_MAJOR ] || { [ "$1" -eq $FLOOR_MAJOR ] && [ "$2" -lt $FLOOR_MINOR ]; } || \
   { [ "$1" -eq $FLOOR_MAJOR ] && [ "$2" -eq $FLOOR_MINOR ] && [ "$3" -lt $FLOOR_PATCH ]; }; then
    if [ "${OMNISSA_UPDATE_ALLOW_BELOW_FLOOR:-}" != "yes" ]; then
        result refused "$TARGET" "below the rollback floor $FLOOR_MAJOR.$FLOOR_MINOR.$FLOOR_PATCH; set OMNISSA_UPDATE_ALLOW_BELOW_FLOOR=yes in the unit to permit a confirmed rollback"
        exit 1
    fi
fi

TOKEN=$(curl -fsS "https://ghcr.io/token?scope=repository:$REPO:pull" 2>/dev/null | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { result failed "$TARGET" "registry token unavailable — registry unreachable?"; exit 1; }
WANT_DIGEST=$(curl -fsSI -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json" \
    "https://ghcr.io/v2/$REPO/manifests/$TARGET" 2>/dev/null | tr -d '\r' | awk 'tolower($1)=="docker-content-digest:"{print $2}')
[ -n "$WANT_DIGEST" ] || { result refused "$TARGET" "the registry has no manifest for $IMAGE:$TARGET"; exit 1; }

# ---- 3. remember where we are -----------------------------------------------------
PREV_LINE=$(grep -nE "^[[:space:]]*image:[[:space:]]*$IMAGE:" "$COMPOSE" | head -1)
[ -n "$PREV_LINE" ] || { result failed "$TARGET" "no image: line for $IMAGE in $COMPOSE"; exit 1; }
PREV_NO=${PREV_LINE%%:*}
PREV_TAG=$(echo "$PREV_LINE" | sed -E "s|.*$IMAGE:([^[:space:]\"']+).*|\1|")
PREV_DIGEST=$(docker image inspect --format '{{index .RepoDigests 0}}' "$(docker inspect --format '{{.Image}}' "$APP")" 2>/dev/null)
log "requested $TARGET (pin now $PREV_TAG, running ${PREV_DIGEST#*@})"
cp -p "$COMPOSE" "$COMPOSE.pre-update"

pin() { sed -i -E "${PREV_NO}s|($IMAGE:)[^[:space:]\"']+|\1$1|" "$COMPOSE"; }
recreate() { docker compose -p "$PROJECT" -f "$COMPOSE" pull >/dev/null 2>&1 && docker compose -p "$PROJECT" -f "$COMPOSE" up -d >/dev/null 2>&1; }
running_digest() { docker image inspect --format '{{index .RepoDigests 0}}' "$(docker inspect --format '{{.Image}}' "$APP" 2>/dev/null)" 2>/dev/null; }
wait_healthy() { i=0; until curl -fsS "$HEALTH_URL/actuator/health" >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 36 ] && return 1; sleep 5; done; }
reported_version() { curl -fsS "$HEALTH_URL/actuator/info" 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p'; }

rollback() { # reason
    log "rolling back to $PREV_TAG: $1"
    pin "$PREV_TAG"
    recreate
    wait_healthy || log "WARNING: container not healthy after rollback — check: docker logs $APP"
    result rolled-back "$TARGET" "$1" "$(running_digest)" "$(reported_version)"
    exit 1
}

# ---- 4. pin, pull, recreate ---------------------------------------------------------
pin "$TARGET"
grep -q "$IMAGE:$TARGET" "$COMPOSE" || { cp -p "$COMPOSE.pre-update" "$COMPOSE"; result failed "$TARGET" "compose rewrite did not take"; exit 1; }
recreate || rollback "pull or recreate failed"

# ---- 5. prove it -------------------------------------------------------------------------
wait_healthy || rollback "container never became healthy"
GOT_DIGEST=$(running_digest)
case "$GOT_DIGEST" in
    *"$WANT_DIGEST") ;;
    *) rollback "running digest ${GOT_DIGEST#*@} does not match the registry's $WANT_DIGEST for $TARGET — the deploy changed nothing" ;;
esac
GOT_VERSION=$(reported_version)
[ "$GOT_VERSION" = "$TARGET" ] || rollback "the application reports version '${GOT_VERSION:-?}', not $TARGET"

# ---- 7. done ---------------------------------------------------------------------------------
rm -f "$COMPOSE.pre-update"
docker image prune -f >/dev/null 2>&1 || :
result deployed "$TARGET" "digest and version verified" "$GOT_DIGEST" "$GOT_VERSION"
