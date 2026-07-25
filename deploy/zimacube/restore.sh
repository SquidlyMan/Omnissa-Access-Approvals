#!/usr/bin/env bash
#
# Restore Access Approval Tool state from a backup archive.
#
#   sudo sh restore.sh /media/ZIMARAID/Backups/OmnissaApprovals/omnissa-approvals-YYYYmmdd-HHMMSS.tar.gz
#   sudo sh restore.sh <archive> --db-only     # database only, keep current env/secrets
#
# The app is stopped for the restore (the H2 file cannot be swapped underneath a
# running app) and started again afterwards. The state being replaced is copied
# aside first, so a bad restore is itself recoverable.
#
set -eu

ARCHIVE="${1:-}"
APP_DIR="${APP_DIR:-/media/ZIMARAID/omnissa-approvals}"
COMPOSE="$APP_DIR/src/deploy/zimacube/docker-compose.yml"
CONTAINER="${CONTAINER:-omnissa-approvals}"
DB_ONLY=0

[ -n "$ARCHIVE" ] || { sed -n '2,12p' "$0"; exit 2; }
[ -f "$ARCHIVE" ] || { echo "ERROR: no such archive: $ARCHIVE" >&2; exit 1; }
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --db-only) DB_ONLY=1 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
tar -xzf "$ARCHIVE" -C "$STAGE"

[ -f "$STAGE/data/omnissa-approval.mv.db" ] || {
  echo "ERROR: archive contains no database" >&2; exit 1; }

echo "=== Archive ==="
cat "$STAGE/MANIFEST.txt" 2>/dev/null || echo "(no manifest)"
echo
printf 'Restore this over the CURRENT state? Type RESTORE to continue: '
read -r answer
[ "$answer" = "RESTORE" ] || { echo "Aborted."; exit 1; }

SAFETY="$APP_DIR/pre-restore-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$SAFETY"
cp "$APP_DIR/data/omnissa-approval.mv.db" "$SAFETY/" 2>/dev/null || true
cp "$APP_DIR/omnissa-approvals.env" "$SAFETY/" 2>/dev/null || true
chmod 700 "$SAFETY"
echo "Current state copied to $SAFETY"

echo "Stopping $CONTAINER…"
docker compose -f "$COMPOSE" stop >/dev/null 2>&1 || docker stop "$CONTAINER" >/dev/null 2>&1 || true

cp "$STAGE/data/omnissa-approval.mv.db" "$APP_DIR/data/omnissa-approval.mv.db"
if [ "$DB_ONLY" = "0" ] && [ -f "$STAGE/omnissa-approvals.env" ]; then
  cp "$STAGE/omnissa-approvals.env" "$APP_DIR/omnissa-approvals.env"
  chmod 600 "$APP_DIR/omnissa-approvals.env"
  echo "Restored env file (secrets)."
else
  echo "Kept the current env file."
fi

echo "Starting $CONTAINER…"
docker compose -f "$COMPOSE" up -d >/dev/null 2>&1 || docker start "$CONTAINER" >/dev/null 2>&1 || true

echo "Waiting for health…"
i=0
while [ "$i" -lt 24 ]; do
  s="$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo unknown)"
  [ "$s" = "healthy" ] && { echo "Restore complete — app is healthy."; exit 0; }
  i=$((i + 1)); sleep 5
done
echo "WARN: app did not report healthy within 2 minutes — check: docker logs $CONTAINER" >&2
echo "Previous state is preserved in $SAFETY"
exit 1
