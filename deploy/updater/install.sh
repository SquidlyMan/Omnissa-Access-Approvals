#!/bin/sh
# Installs the approved-updates consumer on any systemd host that runs the
# Access Approval Tool from Docker Compose — the part of deploy.sh that is not
# ZimaCube-specific. Run as root, from a checkout of the repository:
#
#   sudo sh deploy/updater/install.sh
#
# Needs: systemd, docker compose v2, curl, flock. The container must already be
# running with the /app/control mount (every shipped compose file has it).
set -eu

APP="${OMNISSA_CONTAINER:-omnissa-approvals}"
HERE=$(cd "$(dirname "$0")/.." && pwd)     # the deploy/ directory
LIB=/usr/local/lib/omnissa-approvals

for tool in docker curl flock systemctl; do
    command -v "$tool" >/dev/null || { echo "missing: $tool"; exit 1; }
done
docker inspect "$APP" >/dev/null 2>&1 || { echo "container $APP is not running; start it first"; exit 1; }

# The host side of /app/control, read from the container so a named volume
# works as well as a bind mount.
CONTROL=$(docker inspect "$APP" --format '{{range .Mounts}}{{if eq .Destination "/app/control"}}{{.Source}}{{end}}{{end}}')
[ -n "$CONTROL" ] || { echo "container $APP has no /app/control mount — add it to the compose file and recreate"; exit 1; }

mkdir -p "$LIB"
install -m 0755 "$HERE/zimacube/update.sh" "$LIB/update.sh"
sed -e "s|__CONTROL_DIR__|$CONTROL|g" -e "s|__SRC_DIR__/deploy/zimacube/update.sh|$LIB/update.sh|g" \
    "$HERE/zimacube/omnissa-approvals-update.service" > /etc/systemd/system/omnissa-approvals-update.service
sed "s|__CONTROL_DIR__|$CONTROL|g" \
    "$HERE/zimacube/omnissa-approvals-update.path" > /etc/systemd/system/omnissa-approvals-update.path
systemctl daemon-reload
systemctl enable --now omnissa-approvals-update.path

echo "updater installed: watching $CONTROL/update-requested"
echo "log: journalctl -u omnissa-approvals-update"
