# Deployment

The Access Approval Tool for Omnissa ships as a single container: Spring Boot
backend + pre-built React frontend, listening on plain HTTP port **8081**.
You put your own TLS-terminating reverse proxy in front of it.

> Not an Omnissa product — see [NOTICE.md](../NOTICE.md). Intended for
> testing/demo use only.

## Container Basics

- **Image**: built from the repository `Dockerfile` (multi-stage: Maven +
  npm build, then a minimal JRE runtime).
- **Port**: `8081` (HTTP). Override with `SERVER_PORT`.
- **State**: the embedded H2 database and any certificate files live under
  `/app/data` — mount a volume there or all requests, rules, users, and
  audit history are lost on container recreate. `/app/config` can optionally
  hold extra Spring config.
- **Configuration**: everything is environment variables. See the
  [configuration reference](configuration.md).

### Pull from GHCR (once published)

```bash
docker pull ghcr.io/squidlyman/omnissa-access-approvals:latest
```

### Or build from source

```bash
git clone https://github.com/SquidlyMan/Omnissa-Access-Approvals.git
cd Omnissa-Access-Approvals
docker build -t omnissa-access-approvals .
```

The first build takes several minutes — Maven downloads dependencies and the
frontend is built with npm inside the image.

### Run

```bash
docker run -d --name omnissa-approvals \
  --env-file omnissa-approvals.env \
  -v /srv/omnissa-approvals/data:/app/data \
  -p 8081:8081 \
  --restart unless-stopped \
  ghcr.io/squidlyman/omnissa-access-approvals:latest
```

Use [`deploy/zimacube/omnissa-approvals.env.example`](../deploy/zimacube/omnissa-approvals.env.example)
as the template for the env file (it is a complete, commented reference) and
`chmod 600` it — it contains client secrets.

The repository also ships ready-made Docker Compose files:

| File | Mode |
|---|---|
| `docker-compose.yml` | App + Caddy sidecar (automatic Let's Encrypt TLS) |
| `docker-compose-standalone.yml` | App terminates TLS itself with your PKCS12 keystore |
| `docker-compose-proxy.yml` | App only, plain HTTP 8081 — you bring the reverse proxy |

Verify with `curl http://<host>:8081/actuator/health` — it should return
`{"status":"UP"}`.

## Reverse Proxy Requirements

- **TLS termination** at the proxy. Omnissa Access will only call out to a
  valid, publicly trusted HTTPS endpoint.
- **Forwarded headers**: pass `X-Forwarded-Proto`, `X-Forwarded-Host`, and
  `X-Forwarded-For`. The app already sets
  `server.forward-headers-strategy=framework`, so it honors them without
  extra configuration — this is how it generates correct `https://` OAuth2
  redirect URIs behind the proxy.
- **SSE**: the live queue uses Server-Sent Events on
  `/api/approvals/stream`. On nginx that location needs `proxy_buffering
  off` (plus `proxy_cache off`, HTTP/1.1, and a long `proxy_read_timeout`)
  or live updates will stall.

Example nginx server block:

```nginx
server {
    listen 443 ssl http2;
    server_name approvals.example.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
    }

    # Server-Sent Events — live queue updates
    location /api/approvals/stream {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 24h;
    }
}
```

With **Nginx Proxy Manager**, create a proxy host pointing at
`http://<host>:8081`, select your certificate, and add the
`/api/approvals/stream` settings as a *Custom Location* if live updates
stall.

## Inbound Connectivity

Only **one** path must be reachable from the internet:

```
POST https://<your-host>/api/approvals/new
```

That is the callout endpoint Omnissa Access (a cloud service) POSTs approval
requests to. It requires:

- a **public DNS** name that resolves from the internet,
- a **valid TLS certificate** (Omnissa Access rejects self-signed/invalid
  certs),
- reachability for `POST` and `OPTIONS` (the Access console sends an
  `OPTIONS` probe when you save the approvals settings).

The **admin UI can stay LAN-only**. A common pattern: expose only
`/api/approvals/new` through your firewall/proxy and keep everything else on
the internal network. The callout endpoint supports optional Basic auth and
per-IP rate limiting — see [SECURITY.md](../SECURITY.md).

## ZimaCube / CasaOS

For a ZimaCube NAS, `deploy/zimacube/` contains a complete idempotent
deployment script — repo checkout (for the compose/env/service assets), env
file, and H2 data on `/media/ZIMARAID/omnissa-approvals/`, a
CasaOS-adoption-safe compose file, and a systemd unit that keeps port 8081
LAN-only via a `DOCKER-USER` iptables rule. The container image is pulled
from `ghcr.io/squidlyman/omnissa-access-approvals` — no local build on the
NAS is required:

```bash
git clone https://github.com/SquidlyMan/Omnissa-Access-Approvals.git /media/ZIMARAID/omnissa-approvals/src
sudo sh /media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/deploy.sh
# first run creates the env file and stops — edit it, then re-run
```

**Updates:** re-run `deploy.sh` (git pull + image pull + recreate), run
`docker compose -f <compose file> pull && docker compose -f <compose file>
up -d` yourself, or opt in to Watchtower auto-updates — see
[Automatic Updates](#automatic-updates-optional-disabled-by-default) below.
To make the CasaOS **"Check and then update"** button work, pin the image to
the moving version tag instead of `:latest` — set `OMNISSA_IMAGE_TAG` to the
current minor line (e.g. `1.4`) in the compose's `.env`; see
[CasaOS updates](#casaos-check-and-then-update) below.

See [`deploy/zimacube/deploy.sh`](../deploy/zimacube/deploy.sh) and the
README's ZimaCube section for the Nginx Proxy Manager wiring and the
Docker-bridge firewall note.

## Backup and Restore

Only two things are not reproducible from GHCR and git:

- **the H2 database** (`data/omnissa-approval.mv.db`) — every request, decision,
  auto-approval rule, audit entry and local admin account;
- **the env file** (`omnissa-approvals.env`) — tenant URL, OAuth client secrets,
  Slack signing secret, SMTP credentials.

[`deploy/zimacube/backup.sh`](../deploy/zimacube/backup.sh) archives both (plus
the compose `.env` and a manifest recording the running image digest) to
`/media/ZIMARAID/Backups/OmnissaApprovals/`.

```bash
sudo sh /media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/backup.sh
sudo sh ... backup.sh --quiesce      # stop the app for a provably-consistent copy
sudo sh ... backup.sh --keep 30      # retention (default: 14 archives)
```

Archives are `chmod 600` in a `chmod 700` directory **because they contain
secrets** — treat a copy of one as equivalent to the env file. Each run verifies
the archive is readable and contains the database before pruning old ones; a
backup that fails verification is deleted rather than left to look valid.

> **Consistency:** the default is a **live** copy taken without stopping the
> container. H2 is a single file being written by a running app, so a live copy
> is not transactionally guaranteed. That trade is deliberate — stopping the
> container drops inbound callouts, and **Omnissa Access does not retry a failed
> push**, so a request arriving during downtime would be lost outright (only
> recoverable later via *Pull from Access*). Use `--quiesce` when you want a
> guaranteed-clean copy and can accept ~30 s of downtime.

### Schedule it

Two systemd units run it nightly at 03:15 (quiet hours, so a live copy is least
likely to catch a write) and catch up after downtime:

```bash
SRC=/media/ZIMARAID/omnissa-approvals/src/deploy/zimacube
sudo cp $SRC/omnissa-approvals-backup.service $SRC/omnissa-approvals-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now omnissa-approvals-backup.timer
systemctl list-timers omnissa-approvals-backup.timer     # confirm it is scheduled
sudo systemctl start omnissa-approvals-backup.service    # run once now
journalctl -u omnissa-approvals-backup.service -n 20     # check the result
```

### Restore

[`deploy/zimacube/restore.sh`](../deploy/zimacube/restore.sh) prints the
archive's manifest, requires you to type `RESTORE`, **copies the current state
aside first** (so a bad restore is itself recoverable), stops the app, swaps the
files in, restarts, and waits for the health check.

```bash
sudo sh restore.sh /media/ZIMARAID/Backups/OmnissaApprovals/omnissa-approvals-YYYYmmdd-HHMMSS.tar.gz
sudo sh restore.sh <archive> --db-only    # database only; keep current secrets
```

Use `--db-only` when the env file has changed since the backup (rotated secrets,
new Slack app) and you only want the data back.

> **Test a restore before you need one.** A backup is only proven by restoring
> it. `restore.sh` preserves the pre-restore state under
> `/media/ZIMARAID/omnissa-approvals/pre-restore-<timestamp>/`, so a rehearsal is
> low-risk — but it does briefly stop the app.

## Automatic Updates (optional, disabled by default)

The ZimaCube compose file
([`deploy/zimacube/docker-compose.yml`](../deploy/zimacube/docker-compose.yml))
ships an optional [Watchtower](https://containrrr.dev/watchtower/) service
behind the `autoupdate` Docker Compose profile. **It does not run unless you
explicitly enable the profile** — a plain `docker compose up -d` (what
`deploy.sh` runs) never starts it.

When enabled, Watchtower polls GHCR once a day; when a new
`ghcr.io/squidlyman/omnissa-access-approvals` image is published, it pulls
it, recreates the approvals container with the same settings, and removes
the superseded image (`WATCHTOWER_CLEANUP`). All application state is
bind-mounted to `/media/ZIMARAID`, so the recreate loses nothing.

**Enable:**

```bash
docker compose -f /media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/docker-compose.yml \
  --profile autoupdate up -d
```

**Disable** (stop and remove only the Watchtower container; the app keeps
running):

```bash
docker compose -f /media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/docker-compose.yml \
  --profile autoupdate down watchtower
```

**Change the poll interval:** the default is daily
(`WATCHTOWER_POLL_INTERVAL: "86400"`, in seconds). Edit that value in the
compose file's `watchtower` service, or set it in a compose override file,
then re-run the enable command.

**Scoping guarantee:** Watchtower runs with `WATCHTOWER_LABEL_ENABLE=true`,
so it only ever manages containers that carry the
`com.centurylinklabs.watchtower.enable: "true"` label — in this deployment,
exactly the `omnissa-approvals` container. Other containers on the host are
never touched.

**Security consideration:** Watchtower needs the Docker socket
(`/var/run/docker.sock`) mounted to pull images and recreate containers.
This is the standard Watchtower deployment model, but a process with the
Docker socket effectively has root-level control of the host's Docker
engine — which is why this ships disabled and opt-in. Only enable it if
that trade-off is acceptable in your environment.

### CasaOS "Check and then update"

CasaOS special-cases the `:latest` tag as "always current" and skips the pull,
so with the default `:latest` image it frequently reports "on the latest
version" while the container is actually stale. It **does** reliably detect a
moved **version** tag, so CI publishes one for it:

- a moving **`major.minor`** tag (e.g. `1.4`) that advances on every `main`
  merge, and
- the immutable full version (e.g. `1.4.1`).

To make the CasaOS update button work, point the deployment at the moving
minor tag. Set it in the compose project's `.env` (next to the compose file,
i.e. `/media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/.env`):

```bash
OMNISSA_IMAGE_TAG=1.4
```

then recreate once (`docker compose … up -d`). From then on, when a new patch
publishes, CasaOS's **Check and then update** sees the moved `1.4` tag and
pulls it. The dashboard shows the running app version, so you can confirm the
update landed. (Bump `OMNISSA_IMAGE_TAG` to the new minor — `1.5`, `1.6`, … —
when the minor version increments.)

If you prefer hands-off updates, use Watchtower (above) instead; it tracks
`:latest` by digest and is unaffected by the CasaOS `:latest` quirk.
