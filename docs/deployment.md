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

### Restrict the proxy to known paths

The proxy pattern is a **security control**. It decides what reaches an
internal system at all, and it is the only control that still applies if the
container is misconfigured, compromised, or exposes something a future release
adds without anyone noticing. Treat it as default-deny: enumerate what is
valid, reject everything else.

Do **not** widen it to `(/.*)` to save maintenance. That delegates the entire
decision to the application and assumes the application is correct — which is
exactly the assumption a defence-in-depth layer exists to avoid making.

The current valid path set:

```
(/|/login(/.*)?|/logout|/oauth2(/.*)?|/dashboard|/queue|/rules|/users(/.*)?|/help|/requests(/.*)?|/assets(/.*)?|/favicon\.ico|/api(/.*)?)
```

Notes on that pattern:

- `/requests(/.*)?` must stay, and must accept a child path — Slack and Teams
  approval buttons are deep links of the form `/requests/{id}?action=approve`.
- `/oauth2(/.*)?` and `/login(/.*)?` cover the authorization redirect and the
  `/login/oauth2/code/omnissa` callback. OAuth login breaks without both.
- **Remove `/settings(/.*)?` if your pattern still carries it.** No such route
  exists in the application. An allow-listed path that resolves to nothing is
  the kind of entry this approach is meant to eliminate.
- `/actuator/health` is deliberately absent — see below.

**Keeping it accurate is the hard part**, and it is a real risk rather than a
theoretical one: a page added to the application but not to the pattern works
when clicked in the UI, because React Router renders it client-side and the
browser never asks the proxy, and 404s when the same URL is refreshed,
bookmarked, or opened from a chat approval button. The server-side copy of this
list drifted twice in one release before it was removed.

So the list is verified rather than remembered. `ProxyPatternCoverageTest`
extracts the routes the application actually declares and asserts that the
pattern above matches every one of them, failing the build when a new page is
added without updating this document. Update the pattern here, and the test
tells you whether it is complete before the release ships — not after an
approver clicks a link that 404s.

Note that `/actuator/health` does **not** belong in the pattern for the UAG's
own health monitor — that connects directly to the internal resource. See
[Monitoring](monitoring.md#unified-access-gateway).

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
Do **not** use the CasaOS **"Check and then update"** button — it always
reports "is the latest version" for this container regardless of the image tag,
because it never checks the registry; see
[CasaOS updates](#casaos-check-and-then-update) below.

See [`deploy/zimacube/deploy.sh`](../deploy/zimacube/deploy.sh) and the
README's ZimaCube section for the Nginx Proxy Manager wiring and the
Docker-bridge firewall note.

### The CasaOS app tile

Before opening an app, CasaOS probes it at **`<scheme>://<hostname>:<port_map>/`**
(`ComposeApp.HealthCheck`, logged as *"checking compose app health at the
specified web port"* in `/var/log/casaos/mod-management.log`). If that probe
fails, clicking the tile silently runs a `pull` + `up -d` — **it recreates the
container instead of opening the UI**. Two things follow.

**1. The port must be reachable from the NAS itself.** With `x-casaos hostname`
unset the probe resolves to `http://127.0.0.1:<port_map>/`, so a container
published only on the LAN IP is unreachable to it. The compose file publishes
8081 on loopback as well for this reason — loopback is host-only and does not
widen exposure.

**2. Behind a TLS reverse proxy, point the tile at the public URL.** CasaOS
builds the link as `scheme://hostname:port_map/index` and **always appends the
port**, so all three must be set together. In the compose project's `.env`:

```bash
WEBUI_SCHEME=https
WEBUI_HOSTNAME=approvals.example.com
WEBUI_PORT=443
```

That yields `https://approvals.example.com:443/` — the public URL. Setting
`WEBUI_HOSTNAME` while leaving the port at 8081 produces
`https://approvals.example.com:8081/`, where nothing listens, and the launch
page reports **Service Unavailable**.

This is worth doing: admin OAuth2 login only works on the registered redirect
URI, so opening the app by NAS host and port breaks *Sign in with Omnissa
Access* (local admin login still works), and a plain-`http` link is blocked as
mixed content when the ZimaOS dashboard itself is served over HTTPS. Leaving
all three unset keeps the old behavior, `http://<nas-host>:8081/`.

These are CasaOS-side settings read from the compose file's `x-casaos` block —
nothing in the application image changes.

## Monitoring

Two health signals, deliberately separate:

| Endpoint | Auth | Answers |
|---|---|---|
| `/actuator/health` | public | **Liveness only** — is the container running? |
| `/api/health/deps` | public | Aggregate dependency status: `UP` / `DEGRADED` / `DOWN` |
| `/api/health/dependencies` | session | Per-component detail |

`/actuator/health` ignores dependencies on purpose: Docker, `deploy.sh`, CasaOS
and the UAG all consume it, and **CasaOS recreates the container** when it
fails. An Omnissa Access outage must not restart a healthy service.

Point the **UAG health monitor** and the **Docker health check** at
`/actuator/health`. Point **Uptime Kuma** at both — an HTTP monitor on
`/actuator/health` for outages, and a Keyword monitor on `/api/health/deps`
matching `"status":"UP"` for warnings.

Checked components: Omnissa Access reachability, scheduler liveness (the only
failure with no other symptom — if the JIT sweeps stall, time-bound access
silently never expires), approval drift against the tenant, and webhook
delivery.

**Full reference, including the Kuma and UAG recipes and a per-component
runbook: [Monitoring](monitoring.md).**

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

**This button does not work for this container, and no tag choice can make it
work.** It always reports *"Access Approval Tool for Omnissa is the latest
version!"* — even when the registry has a newer image. Do not use it to decide
whether you are up to date.

The reason is not a digest comparison going wrong; there is no digest
comparison at all. ZimaOS resolves "is an update available?" by looking the app
up in a **CasaOS AppStore**, and this container is externally managed (deployed
by Compose, not installed from a store). The check bails out immediately —
visible in `/var/log/casaos/mod-management.log`:

```
info   app not found in any appstore                      {"id": "omnissa-approvals"}
error  store compose app not found, thus no update available
       {"func": "service.(*AppStoreManagement).isUpdateAvailable", "storeAppID": "omnissa-approvals"}
```

Because the lookup fails before any registry call, the result is the same for
`:latest`, for a pinned `major.minor` tag, and for a full version tag. Verified
on ZimaOS with the container pinned to `1.9` while the registry `1.9` tag
pointed at a newer digest: the button still reported "latest version".

**Use one of these instead:**

- re-run `deploy.sh` (git pull + image pull + recreate), or
- `docker compose -f <compose file> pull && docker compose -f <compose file> up -d`, or
- enable the `autoupdate` Watchtower profile above for hands-off updates.

The **dashboard version banner** is the authoritative answer to "what am I
running?" — check it there, not in CasaOS.

#### `OMNISSA_IMAGE_TAG`

CI publishes a moving **`major.minor`** tag (e.g. `1.19`) that advances on every
`main` merge, plus the immutable full version (e.g. `1.19.2`). Pinning is still
worth doing — it makes deployments deterministic and keeps you on one minor
line — but it is **not** a fix for the CasaOS button. Set it in the compose
project's `.env` (next to the compose file, i.e.
`/media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/.env`):

```bash
OMNISSA_IMAGE_TAG=1.19
```

Bump it only when the **minor** increments — `1.20`, `1.21`, …. A patch
release such as 1.19.1 → 1.19.2 moves the `1.19` tag, so a `compose pull`
picks it up with no pin change. Compare what you are running against the registry with:

```bash
docker image inspect ghcr.io/squidlyman/omnissa-access-approvals:1.9 \
  --format '{{index .RepoDigests 0}}'
```

If that digest differs from the registry's, pull and recreate.
