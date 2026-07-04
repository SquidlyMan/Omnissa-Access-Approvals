#!/bin/sh
# Omnissa Access Approvals — ZimaCube deploy/update script.
# Run ON the ZimaCube as root:  sudo sh deploy/zimacube/deploy.sh
# First run bootstraps everything (repo checkout for compose/env/service
# assets, env file, firewall unit). Idempotent: update by re-running this
# script (git pull + image pull + recreate), or opt in to Watchtower
# auto-updates via the "autoupdate" compose profile (see below). The CasaOS
# "Check and then update" button does NOT reliably detect new GHCR images
# for this externally-managed container — don't rely on it.
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
mkdir -p "$RAID_DIR/data" "$RAID_DIR/docker-config"
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

echo "==> Pulling image from GHCR"
docker compose -f "$SRC_DIR/deploy/zimacube/docker-compose.yml" pull

echo "==> Starting container"
# Optional auto-updates (disabled by default): run the same compose commands
# with `--profile autoupdate` to also start the Watchtower service, e.g.
#   docker compose -f "$SRC_DIR/deploy/zimacube/docker-compose.yml" --profile autoupdate up -d
# To disable again:
#   docker compose -f "$SRC_DIR/deploy/zimacube/docker-compose.yml" --profile autoupdate down watchtower
# then the normal `up -d` below. See docs/deployment.md "Automatic Updates".
printf 'LAN_IP=%s\n' "$LAN_IP" > "$SRC_DIR/deploy/zimacube/.env"
docker compose -f "$SRC_DIR/deploy/zimacube/docker-compose.yml" up -d

echo "==> Firewall persistence (LAN-only on 8081)"
sed "s|__LAN_SUBNET__|$LAN_SUBNET|g" "$SRC_DIR/deploy/zimacube/$APP-fw.service" > /etc/systemd/system/$APP-fw.service
systemctl daemon-reload
systemctl enable --now $APP-fw.service

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
ls -ld "$RAID_DIR/data" && echo "  state: on RAID"

echo ""
echo "Done. Next: point your TLS reverse proxy at  http://$LAN_IP:8081"
echo "(add an /api/approvals/stream location with proxy_buffering off if live"
echo " queue updates stall — see README 'Deploying Behind Your Own Reverse Proxy')."
