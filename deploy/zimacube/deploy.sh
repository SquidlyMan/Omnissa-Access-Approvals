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
#   - port 8081 published on the LAN IP, locked to 10.88.88.0/24 via DOCKER-USER
#     with a systemd unit so the rule survives docker/NAS restarts

set -eu

APP=omnissa-approvals
RAID_DIR=/media/ZIMARAID/$APP
SRC_DIR=$RAID_DIR/src
ENV_FILE=$RAID_DIR/$APP.env
REPO_URL=https://github.com/squidlyman/Omnissa-Access-Approvals.git
BRANCH=main

[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }

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
docker compose -f "$SRC_DIR/deploy/zimacube/docker-compose.yml" up -d

echo "==> Firewall persistence (LAN-only on 8081)"
cp "$SRC_DIR/deploy/zimacube/$APP-fw.service" /etc/systemd/system/$APP-fw.service
systemctl daemon-reload
systemctl enable --now $APP-fw.service

echo "==> Verify"
sleep 5
docker ps --filter name=$APP --format '  container: {{.Names}} {{.Status}}'
i=0
until curl -sf http://10.88.88.30:8081/actuator/health >/dev/null 2>&1; do
    i=$((i+1))
    [ $i -ge 24 ] && { echo "  health: NOT RESPONDING after 2 min — check: docker logs $APP"; exit 1; }
    sleep 5
done
echo "  health: $(curl -s http://10.88.88.30:8081/actuator/health)"
iptables -C DOCKER-USER -p tcp --dport 8081 ! -s 10.88.88.0/24 -j DROP && echo "  firewall: rule active"
systemctl is-enabled $APP-fw.service >/dev/null && echo "  firewall: unit enabled"
ls -ld "$RAID_DIR/data" && echo "  state: on RAID"

echo ""
echo "Done. Next: add the NPM proxy host  approvals.flaming.ws -> http://10.88.88.30:8081"
echo "(wildcard cert; add /api/approvals/stream custom location with proxy_buffering off"
echo " if live queue updates stall — see README 'Deploying Behind Your Own Reverse Proxy')."
