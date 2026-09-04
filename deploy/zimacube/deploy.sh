#!/bin/sh
# Omnissa Access Approvals — ZimaCube deploy/update script.
# Run ON the ZimaCube as root:
#   sudo sh deploy/zimacube/deploy.sh            # first install, or re-apply
#   sudo sh deploy/zimacube/deploy.sh 1.22.0     # pin and deploy that version
#
# First run bootstraps everything (repo checkout for compose/env/service
# assets, env file, firewall unit, the updater units). Idempotent.
#
# The image is pinned to an IMMUTABLE full version (1.22.0), never the moving
# minor line (1.22) — so nothing that pulls can change what is running except
# an explicit choice. That means "docker compose pull && up -d" is NOT an
# upgrade path any more: it re-pulls the same digest, prints Pull complete,
# exits 0, and changes nothing. Upgrade by approving a version in the console
# (the updater applies it), or by passing the version to this script.
#
# The CasaOS "Check and then update" button does NOT work for this container
# — ZimaOS looks the app up in a CasaOS AppStore to decide if an update
# exists, and an externally-managed Compose app is never found there. The
# console's own update banner is the replacement.
#
# Follows the zimacube-container-deploy runbook:
#   - nothing written to / (rootfs is 1.2 GB and ~full)
#   - source, env file, and H2 data live on /media/ZIMARAID/omnissa-approvals
#   - image + layers go to the NVMe docker root as normal
#   - port 8081 published on the LAN IP, locked to the LAN subnet via
#     DOCKER-USER with a systemd unit so the rule survives docker/NAS restarts
#
# LAN_IP / LAN_SUBNET are auto-detected. To override, create
# /media/ZIMARAID/omnissa-approvals/deploy.conf with e.g.:
#   LAN_IP=192.168.1.50
#   LAN_SUBNET=192.168.1.0/24

set -eu

APP=omnissa-approvals
VERSION="${1:-}"
if [ -n "$VERSION" ] && ! echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "Version must be N.N.N (got '$VERSION')"; exit 1
fi
RAID_DIR=/media/ZIMARAID/$APP
SRC_DIR=$RAID_DIR/src
ENV_FILE=$RAID_DIR/$APP.env
REPO_URL=https://github.com/squidlyman/Omnissa-Access-Approvals.git
BRANCH=main

[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }

# LAN address handling: auto-detect, allow override via deploy.conf
[ -f "$RAID_DIR/deploy.conf" ] && . "$RAID_DIR/deploy.conf"
LAN_IP="${LAN_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}' | head -1)}"
[ -n "$LAN_IP" ] || { echo "Could not detect LAN IP - set LAN_IP in $RAID_DIR/deploy.conf"; exit 1; }
LAN_SUBNET="${LAN_SUBNET:-${LAN_IP%.*}.0/24}"
echo "==> LAN: $LAN_IP (subnet $LAN_SUBNET)"

echo "==> Directories on RAID"
# control/ is the host side of the container's /app/control mount — where an
# approved update is written for the updater. Kept apart from data/, which
# backup archives, so a restored archive can never carry a pending deploy.
mkdir -p "$RAID_DIR/data" "$RAID_DIR/control" "$RAID_DIR/docker-config"

# One deploy at a time — shared with update.sh, so a console approval and a
# hand-run deploy cannot interleave edits to the same compose file.
exec 9>"$RAID_DIR/.deploy.lock"
flock -w 900 9 || { echo "another deploy has held the lock for 15 minutes; giving up"; exit 1; }
# / is a read-only squashfs on ZimaOS — docker/git must never write to $HOME (/root).
export DOCKER_CONFIG="$RAID_DIR/docker-config"
export HOME="$RAID_DIR"

echo "==> Source checkout"
if [ -d "$SRC_DIR/.git" ]; then
    git -C "$SRC_DIR" fetch origin "$BRANCH"
    git -C "$SRC_DIR" checkout "$BRANCH"
    git -C "$SRC_DIR" reset --hard "origin/$BRANCH"
else
    git clone --branch "$BRANCH" "$REPO_URL" "$SRC_DIR"
fi

echo "==> Env file"
if [ ! -f "$ENV_FILE" ]; then
    cp "$SRC_DIR/deploy/zimacube/$APP.env.example" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo ""
    echo "  Created $ENV_FILE from template."
    echo "  EDIT IT NOW (client secrets + admin password), then re-run this script."
    exit 0
fi
chmod 600 "$ENV_FILE"

# Which compose file owns the container? Once CasaOS has adopted the app, ITS
# copy under /var/lib/casaos/apps/<random-id>/ does, and this repository's file
# governs nothing — a pull driven from here would only appear to work. Ask the
# running container; fall back to this file only for a first install.
COMPOSE=$(docker inspect "$APP" --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' 2>/dev/null || :)
PROJECT=$(docker inspect "$APP" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || :)
[ -n "$COMPOSE" ] && [ -f "$COMPOSE" ] || { COMPOSE="$SRC_DIR/deploy/zimacube/docker-compose.yml"; PROJECT=""; }
echo "==> Compose file in charge: $COMPOSE${PROJECT:+ (project $PROJECT)}"

# Refresh LAN_IP in this repo's compose .env WITHOUT discarding anything else
# in it — OMNISSA_IMAGE_TAG and the WEBUI_* tile settings live here too, and a
# plain `>` redirect silently reverted them on every run.
COMPOSE_ENV="$SRC_DIR/deploy/zimacube/.env"
touch "$COMPOSE_ENV"
grep -v '^LAN_IP=' "$COMPOSE_ENV" > "$COMPOSE_ENV.tmp" 2>/dev/null || :
printf 'LAN_IP=%s\n' "$LAN_IP" >> "$COMPOSE_ENV.tmp"
mv "$COMPOSE_ENV.tmp" "$COMPOSE_ENV"

IMAGE=ghcr.io/squidlyman/omnissa-access-approvals
REPO=${IMAGE#ghcr.io/}
if [ -n "$VERSION" ]; then
    # Prove the version exists BEFORE touching the pin. A typo that got as far
    # as `pull` would leave the compose file pointing at nothing, and the next
    # recreate — CasaOS does them on its own — would fail on it.
    TOKEN=$(curl -fsS "https://ghcr.io/token?scope=repository:$REPO:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
    [ -n "$TOKEN" ] || { echo "registry token unavailable — is ghcr.io reachable?"; exit 1; }
    WANT_DIGEST=$(curl -fsSI -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json" \
        "https://ghcr.io/v2/$REPO/manifests/$VERSION" 2>/dev/null | tr -d '\r' | awk 'tolower($1)=="docker-content-digest:"{print $2}')
    [ -n "$WANT_DIGEST" ] || { echo "no such published version: $IMAGE:$VERSION"; exit 1; }
    echo "==> Pinning image to $VERSION ($WANT_DIGEST)"
    if grep -qE "^[[:space:]]*image:[[:space:]]*$IMAGE:" "$COMPOSE"; then
        # The adopted copy carries the tag as a literal on its image: line.
        sed -i -E "s|(image:[[:space:]]*$IMAGE:)[^[:space:]\"']+|\1$VERSION|" "$COMPOSE"
    fi
    # This repo's file interpolates it from .env instead.
    grep -v '^OMNISSA_IMAGE_TAG=' "$COMPOSE_ENV" > "$COMPOSE_ENV.tmp" 2>/dev/null || :
    printf 'OMNISSA_IMAGE_TAG=%s\n' "$VERSION" >> "$COMPOSE_ENV.tmp"
    mv "$COMPOSE_ENV.tmp" "$COMPOSE_ENV"
fi

echo "==> Pulling image from GHCR"
docker compose ${PROJECT:+-p "$PROJECT"} -f "$COMPOSE" pull

echo "==> Starting container"
docker compose ${PROJECT:+-p "$PROJECT"} -f "$COMPOSE" up -d

echo "==> Firewall persistence (LAN-only on 8081)"
sed "s|__LAN_SUBNET__|$LAN_SUBNET|g" "$SRC_DIR/deploy/zimacube/$APP-fw.service" > /etc/systemd/system/$APP-fw.service

echo "==> Updater (applies approvals from the console)"
# The path unit watches the HOST side of the container's /app/control mount,
# read from the container so a re-import that moves the compose cannot orphan it.
CONTROL=$(docker inspect "$APP" --format '{{range .Mounts}}{{if eq .Destination "/app/control"}}{{.Source}}{{end}}{{end}}' 2>/dev/null || :)
[ -n "$CONTROL" ] || CONTROL="$RAID_DIR/control"
sed -e "s|__CONTROL_DIR__|$CONTROL|g" -e "s|__SRC_DIR__|$SRC_DIR|g" "$SRC_DIR/deploy/zimacube/$APP-update.service" > /etc/systemd/system/$APP-update.service
sed "s|__CONTROL_DIR__|$CONTROL|g" "$SRC_DIR/deploy/zimacube/$APP-update.path" > /etc/systemd/system/$APP-update.path
systemctl daemon-reload
systemctl enable --now $APP-fw.service
systemctl enable --now $APP-update.path

echo "==> Verify"
sleep 5
docker ps --filter name=$APP --format '  container: {{.Names}} {{.Status}}'
i=0
until curl -sf "http://$LAN_IP:8081/actuator/health" >/dev/null 2>&1; do
    i=$((i+1))
    [ $i -ge 24 ] && { echo "  health: NOT RESPONDING after 2 min — check: docker logs $APP"; exit 1; }
    sleep 5
done
echo "  health: $(curl -s "http://$LAN_IP:8081/actuator/health")"
iptables -C DOCKER-USER -p tcp --dport 8081 ! -s "$LAN_SUBNET" -j DROP && echo "  firewall: rule active"
systemctl is-enabled $APP-fw.service >/dev/null && echo "  firewall: unit enabled"
systemctl is-active $APP-update.path >/dev/null && echo "  updater: watching $CONTROL/update-requested"
ls -ld "$RAID_DIR/data" && echo "  state: on RAID"
REPORTED=$(curl -s "http://$LAN_IP:8081/actuator/info" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
RUNNING=$(docker image inspect --format '{{index .RepoDigests 0}}' "$(docker inspect --format '{{.Image}}' $APP)")
echo "  version: ${REPORTED:-(none reported — pre-1.22.0 image)}"
echo "  digest:  ${RUNNING#*@}"
if [ -n "$VERSION" ]; then
    # Tell the console what happened, the same way update.sh does, so a
    # deploy made from here clears a stale "rolled back" verdict from an
    # earlier approval — and a mismatch is not hidden behind an exit 0.
    case "$RUNNING" in
        *"$WANT_DIGEST") OUTCOME=deployed; REASON="deployed from the host by deploy.sh; digest verified" ;;
        *) OUTCOME=failed; REASON="deploy.sh pinned $VERSION but the running digest ${RUNNING#*@} is not the registry's $WANT_DIGEST" ;;
    esac
    { printf 'outcome=%s\ntarget=%s\nreason=%s\ndigest=%s\nversion=%s\nat=%s\n' \
        "$OUTCOME" "$VERSION" "$REASON" "$RUNNING" "${REPORTED:-}" "$(date -u +%FT%TZ)"; } > "$CONTROL/update-result.tmp" \
        && mv -f "$CONTROL/update-result.tmp" "$CONTROL/update-result"
    echo "  result:  $OUTCOME — $REASON"
    # The updater rolls back to the last PROVEN version, which this now is.
    [ "$OUTCOME" = deployed ] && printf '%s %s\n' "$VERSION" "$RUNNING" > "$CONTROL/last-known-good"
    [ "$OUTCOME" = deployed ] || { echo "  This script does not roll back; to return: sudo sh deploy.sh <previous version>"; exit 1; }
fi

echo ""
echo "Done. Next: point your TLS reverse proxy at  http://$LAN_IP:8081"
echo "(add an /api/approvals/stream location with proxy_buffering off if live"
echo " queue updates stall — see README 'Deploying Behind Your Own Reverse Proxy')."
