#!/usr/bin/env bash
#
# Back up the Access Approval Tool's persistent state: the H2 database (every
# request, decision, rule, audit entry and local user) and the env file (tenant
# URL, OAuth client secrets, Slack signing secret, SMTP credentials). Everything
# else — the image, the compose file, the repo — is reproducible from GHCR/git.
#
#   sudo sh backup.sh                 # live snapshot (no downtime)
#   sudo sh backup.sh --quiesce       # stop the app for a guaranteed-consistent copy
#   sudo sh backup.sh --keep 30       # retention (default 14 archives)
#   sudo sh backup.sh --dir /path     # alternate destination
#
# CONSISTENCY: the default is a live copy, taken without stopping the container.
# H2 is a single file being written by a running app, so a live copy is not
# transactionally guaranteed — a snapshot taken mid-write could lose the last
# write or, rarely, fail to open. That trade is deliberate: stopping the
# container drops inbound callouts, and Omnissa Access does NOT retry a failed
# push, so a request arriving during downtime would be lost entirely (it could
# only be recovered later with "Pull from Access"). Use --quiesce when you want
# a provably clean copy and can accept ~30s of downtime.
#
set -eu

APP_DIR="${APP_DIR:-/media/ZIMARAID/omnissa-approvals}"
BACKUP_DIR="${BACKUP_DIR:-/media/ZIMARAID/Backups/OmnissaApprovals}"
COMPOSE="$APP_DIR/src/deploy/zimacube/docker-compose.yml"
CONTAINER="${CONTAINER:-omnissa-approvals}"
KEEP=14
QUIESCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --quiesce) QUIESCE=1 ;;
    --keep)    KEEP="$2"; shift ;;
    --dir)     BACKUP_DIR="$2"; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

STAMP="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$BACKUP_DIR/omnissa-approvals-$STAMP.tar.gz"
STAGE="$(mktemp -d)"
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if [ ! -f "$APP_DIR/data/omnissa-approval.mv.db" ]; then
  echo "ERROR: no database at $APP_DIR/data/omnissa-approval.mv.db" >&2
  exit 1
fi

# --- optional quiesce: stop the app so the H2 file is closed -----------------
RESTART_NEEDED=0
if [ "$QUIESCE" = "1" ]; then
  echo "Stopping $CONTAINER for a consistent snapshot…"
  docker compose -f "$COMPOSE" stop >/dev/null 2>&1 || docker stop "$CONTAINER" >/dev/null 2>&1 || true
  RESTART_NEEDED=1
  # Bring the app back even if the copy below fails.
  trap 'docker compose -f "$COMPOSE" start >/dev/null 2>&1 || docker start "$CONTAINER" >/dev/null 2>&1 || true; cleanup' EXIT
fi

# --- collect state ----------------------------------------------------------
mkdir -p "$STAGE/data"
cp "$APP_DIR/data/omnissa-approval.mv.db" "$STAGE/data/"
cp "$APP_DIR/omnissa-approvals.env" "$STAGE/" 2>/dev/null || echo "WARN: env file not found" >&2
# Compose-dir .env carries LAN_IP and the pinned OMNISSA_IMAGE_TAG.
cp "$APP_DIR/src/deploy/zimacube/.env" "$STAGE/compose.env" 2>/dev/null || true

# Manifest — what this archive is and what it was taken from.
{
  echo "created:        $(date -Is)"
  echo "host:           $(hostname)"
  echo "consistency:    $([ "$QUIESCE" = "1" ] && echo 'quiesced (container stopped)' || echo 'live copy (app running)')"
  echo "image:          $(docker inspect --format '{{.Config.Image}}' "$CONTAINER" 2>/dev/null || echo unknown)"
  echo "image-digest:   $(docker inspect --format '{{.Image}}' "$CONTAINER" 2>/dev/null || echo unknown)"
  echo "db-bytes:       $(wc -c < "$STAGE/data/omnissa-approval.mv.db")"
  echo "restore:        see docs/deployment.md#backup-and-restore"
} > "$STAGE/MANIFEST.txt"

if [ "$RESTART_NEEDED" = "1" ]; then
  echo "Starting $CONTAINER…"
  docker compose -f "$COMPOSE" start >/dev/null 2>&1 || docker start "$CONTAINER" >/dev/null 2>&1 || true
  trap cleanup EXIT
fi

# --- archive ----------------------------------------------------------------
tar -czf "$ARCHIVE" -C "$STAGE" .
chmod 600 "$ARCHIVE"   # contains client secrets and the Slack signing secret

# --- verify: an unreadable backup is worse than none ------------------------
if ! tar -tzf "$ARCHIVE" >/dev/null 2>&1; then
  echo "ERROR: archive failed verification, removing: $ARCHIVE" >&2
  rm -f "$ARCHIVE"
  exit 1
fi
for want in ./data/omnissa-approval.mv.db ./MANIFEST.txt; do
  if ! tar -tzf "$ARCHIVE" | grep -qx "$want"; then
    echo "ERROR: archive is missing $want, removing: $ARCHIVE" >&2
    rm -f "$ARCHIVE"
    exit 1
  fi
done

# --- retention --------------------------------------------------------------
COUNT="$(ls -1 "$BACKUP_DIR"/omnissa-approvals-*.tar.gz 2>/dev/null | wc -l | tr -d ' ')"
if [ "$COUNT" -gt "$KEEP" ]; then
  ls -1t "$BACKUP_DIR"/omnissa-approvals-*.tar.gz | tail -n +"$((KEEP + 1))" | while read -r old; do
    echo "Pruning $old"
    rm -f "$old"
  done
fi

echo "Backup OK: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1)), keeping $KEEP, $COUNT total"
