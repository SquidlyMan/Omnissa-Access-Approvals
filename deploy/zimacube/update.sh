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
#   7. Write the outcome next to the request for the console to show. The
#      request itself was consumed (renamed to update-applying) at the start,
#      so the path unit cannot re-fire and a new request cannot be lost. A
#      deploy that changed nothing is a FAILURE.

set -u

APP=omnissa-approvals
REPO="${OMNISSA_UPDATE_REGISTRY_REPO:-squidlyman/omnissa-access-approvals}"
IMAGE="ghcr.io/$REPO"
FLOOR_MAJOR=1; FLOOR_MINOR=19; FLOOR_PATCH=5

log() { printf '%s update: %s\n' "$(date -u +%FT%TZ)" "$*"; }

# ZimaOS: / is read-only, so nothing docker does may touch $HOME. On any
# other host the defaults are fine and the script runs unchanged.
if [ -d "/media/ZIMARAID/$APP" ]; then
    export DOCKER_CONFIG=/media/ZIMARAID/$APP/docker-config
    export HOME=/media/ZIMARAID/$APP
fi

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

APPLYING="$CONTROL/update-applying"

[ -f "$INTENT" ] || { log "nothing requested ($INTENT absent)"; exit 0; }

# One deploy at a time on this host, whether it came from the console or from
# deploy.sh run by hand. Two of them interleaving edits to the same compose
# file is the one way to end up pinned to a version nobody chose.
# The lock lives beside the control directory — on the ZimaCube that is
# /media/ZIMARAID/omnissa-approvals/.deploy.lock, the same file deploy.sh takes.
exec 9>"$(dirname "$CONTROL")/.deploy.lock"
flock -w 900 9 || { log "another deploy has held the lock for 15 minutes; giving up"; exit 1; }

# Consume the request NOW, by renaming it: the console shows "applying" for
# as long as update-applying exists, the path unit stops re-firing, and a new
# request written during this run lands in update-requested untouched instead
# of being deleted by this run's clean-up.
TARGET=$(head -n1 "$INTENT" | tr -d '[:space:]')
CONFIRMED=no; grep -qx 'confirmed=below-floor' "$INTENT" && CONFIRMED=yes
[ -f "$APPLYING" ] && log "a previous run did not finish ($(head -n1 "$APPLYING" 2>/dev/null)); the compose pin may be unverified — the rollback target is the last PROVEN version"
mv -f "$INTENT" "$APPLYING"

# Every exit after this point goes through result(): the verdict is written
# for the console and the applying marker is removed, whatever happened.
result() { # outcome target reason [digest] [version]
    reason=$(printf '%s' "$3" | tr -d '\r\n')   # one line — the console parses key=value per line
    {
        printf 'outcome=%s\n' "$1"
        printf 'target=%s\n' "$2"
        printf 'reason=%s\n' "$reason"
        printf 'digest=%s\n' "${4:-}"
        printf 'version=%s\n' "${5:-}"
        printf 'at=%s\n' "$(date -u +%FT%TZ)"
    } > "$RESULT.tmp" && mv -f "$RESULT.tmp" "$RESULT"
    rm -f "$APPLYING"
    log "$1: $2 — $reason"
}

# ---- 1. the compose file that owns the container -----------------------------
COMPOSE=$(docker inspect "$APP" --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' 2>/dev/null || :)
PROJECT=$(docker inspect "$APP" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || :)
[ -n "$COMPOSE" ] && [ -f "$COMPOSE" ] && [ -n "$PROJECT" ] || { result failed "$TARGET" "cannot resolve the compose file from the container's labels — is $APP running?"; exit 1; }

# Health is checked over the LAN address, the way deploy.sh does: the LAN-only
# firewall rule in DOCKER-USER can drop a loopback probe that hairpins through
# the bridge, and a probe that cannot connect would look like a failed deploy.
LAN_IP="${LAN_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}' | head -1)}"
if [ -z "${OMNISSA_UPDATE_HEALTH_URL:-}" ] && [ -z "$LAN_IP" ]; then
    # Falling back to loopback here would turn a network hiccup into a
    # "failed deploy" and a needless rollback. Say so and stop instead.
    result failed "$TARGET" "cannot detect the LAN address for the health probe; set OMNISSA_UPDATE_HEALTH_URL in the unit"
    exit 1
fi
HEALTH_URL="${OMNISSA_UPDATE_HEALTH_URL:-http://$LAN_IP:8081}"

# ---- 2. the request, validated ----------------------------------------------------
echo "$TARGET" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { result refused "$TARGET" "not a release version (expected N.N.N)"; exit 1; }

# The same floor the console enforces. The console makes an administrator
# type the version to go below it and records that in the request as a second
# line; a request without that line — dropped in by hand, or from a console
# that skipped the dialog — is refused here too.
# From here on $1 $2 $3 are the TARGET's major, minor and patch — the script
# takes no arguments of its own, so the positional parameters are free.
set -- $(echo "$TARGET" | tr '.' ' ')
if [ "$1" -lt $FLOOR_MAJOR ] || { [ "$1" -eq $FLOOR_MAJOR ] && [ "$2" -lt $FLOOR_MINOR ]; } || \
   { [ "$1" -eq $FLOOR_MAJOR ] && [ "$2" -eq $FLOOR_MINOR ] && [ "$3" -lt $FLOOR_PATCH ]; }; then
    if [ "$CONFIRMED" != yes ]; then
        result refused "$TARGET" "below the rollback floor $FLOOR_MAJOR.$FLOOR_MINOR.$FLOOR_PATCH and not confirmed; approve it in the console, which asks for the version to be typed"
        exit 1
    fi
    log "$TARGET is below the floor; confirmed by the console"
fi

TOKEN=$(curl -fsS "https://ghcr.io/token?scope=repository:$REPO:pull" 2>/dev/null | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { result failed "$TARGET" "registry token unavailable — registry unreachable?"; exit 1; }
WANT_DIGEST=$(curl -fsSI -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json" \
    "https://ghcr.io/v2/$REPO/manifests/$TARGET" 2>/dev/null | tr -d '\r' | awk 'tolower($1)=="docker-content-digest:"{print $2}')
[ -n "$WANT_DIGEST" ] || { result refused "$TARGET" "the registry has no manifest for $IMAGE:$TARGET"; exit 1; }

# ---- 3. remember where we are -----------------------------------------------------
PREV_LINE=$(grep -E "^[[:space:]]*image:[[:space:]]*$IMAGE:" "$COMPOSE" | head -1)
[ -n "$PREV_LINE" ] || { result failed "$TARGET" "no image: line for $IMAGE in $COMPOSE"; exit 1; }
PREV_TAG=$(echo "$PREV_LINE" | sed -E "s|.*$IMAGE:([^[:space:]\"']+).*|\1|")
# The rollback target is the last version this host PROVED, not whatever the
# compose file says now: a run that died between pinning and verifying leaves
# the file pointing at a version nobody has seen work.
LKG="$CONTROL/last-known-good"
if [ -f "$LKG" ]; then
    LKG_TAG=$(awk 'NR==1{print $1}' "$LKG")
    [ -n "$LKG_TAG" ] && [ "$LKG_TAG" != "$PREV_TAG" ] && log "compose is pinned to $PREV_TAG but the last proven version is $LKG_TAG; rolling back to the proven one if needed"
    [ -n "$LKG_TAG" ] && PREV_TAG="$LKG_TAG"
fi
echo "$PREV_TAG" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || log "WARNING: the rollback target '$PREV_TAG' is a moving tag — pin a full version (deploy.sh <version>) so a rollback lands somewhere known"
PREV_DIGEST=$(docker image inspect --format '{{index .RepoDigests 0}}' "$(docker inspect --format '{{.Image}}' "$APP")" 2>/dev/null)
log "requested $TARGET (pin now $PREV_TAG, running ${PREV_DIGEST#*@})"
cp -p "$COMPOSE" "$COMPOSE.pre-update"

# The pin is rewritten by PATTERN, at write time, through a sibling file:
# not by a line number captured minutes earlier (CasaOS rewrites its copy on
# its own schedule), and not with sed -i, whose flags differ between GNU and
# BSD sed.
pin() { sed -E "s|^([[:space:]]*image:[[:space:]]*$IMAGE:)[^[:space:]\"']+|\1$1|" "$COMPOSE" > "$COMPOSE.new" && mv -f "$COMPOSE.new" "$COMPOSE"; }
recreate() { docker compose -p "$PROJECT" -f "$COMPOSE" pull >/dev/null 2>&1 && docker compose -p "$PROJECT" -f "$COMPOSE" up -d >/dev/null 2>&1; }
running_digest() { docker image inspect --format '{{index .RepoDigests 0}}' "$(docker inspect --format '{{.Image}}' "$APP" 2>/dev/null)" 2>/dev/null; }
wait_healthy() { i=0; until curl -fsS "$HEALTH_URL/actuator/health" >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 36 ] && return 1; sleep 5; done; }
reported_version() { curl -fsS "$HEALTH_URL/actuator/info" 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p'; }

rollback() { # reason
    log "rolling back to $PREV_TAG: $1"
    pin "$PREV_TAG"
    # The previous image is still local, so recreate even if the registry has
    # gone away between the pull and now — a failed re-pull must not leave the
    # container down.
    docker compose -p "$PROJECT" -f "$COMPOSE" pull >/dev/null 2>&1 || log "re-pull of $PREV_TAG failed; recreating from the local image"
    back=ok
    docker compose -p "$PROJECT" -f "$COMPOSE" up -d >/dev/null 2>&1 || back="recreate of $PREV_TAG failed"
    [ "$back" = ok ] && { wait_healthy || back="$PREV_TAG never became healthy"; }
    if [ "$back" = ok ]; then
        result rolled-back "$TARGET" "$1" "$(running_digest)" "$(reported_version)"
    else
        # Not the same thing as a clean revert, and must not look like one:
        # the service may be down. Check: docker logs $APP
        result rollback-failed "$TARGET" "$1 — and the rollback did not come back up: $back" "$(running_digest)" "$(reported_version)"
    fi
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
# /actuator/info exists from 1.22.0. An older target cannot report its
# version, so a rollback to one rests on the digest check alone — otherwise
# every such rollback would be reverted for a check the target cannot pass.
GOT_VERSION=$(reported_version)
if [ "$GOT_VERSION" != "$TARGET" ]; then
    if [ "$1" -gt 1 ] || { [ "$1" -eq 1 ] && [ "$2" -ge 22 ]; }; then
        rollback "the application reports version '${GOT_VERSION:-?}', not $TARGET"
    fi
    log "$TARGET predates /actuator/info; accepted on digest alone"
    GOT_VERSION="$TARGET (by digest)"
fi

# ---- 7. done ---------------------------------------------------------------------------------
rm -f "$COMPOSE.pre-update"
# Deliberately no `docker image prune`: this is a shared host, and the
# previous tag stays cached so a rollback does not depend on the registry.
printf '%s %s\n' "$TARGET" "$GOT_DIGEST" > "$LKG.tmp" && mv -f "$LKG.tmp" "$LKG"
result deployed "$TARGET" "digest and version verified" "$GOT_DIGEST" "$GOT_VERSION"
