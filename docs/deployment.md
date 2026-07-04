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
Do **not** rely on the CasaOS "Check and then update" button (explained in
that section).

See [`deploy/zimacube/deploy.sh`](../deploy/zimacube/deploy.sh) and the
README's ZimaCube section for the Nginx Proxy Manager wiring and the
Docker-bridge firewall note.

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

**Why not CasaOS "Check and then update"?** For externally-managed
containers like this one, the CasaOS update button does **not** reliably
detect registry updates — it compares against the local image cache rather
than checking GHCR for a newer digest, so it frequently reports
"up to date" when a newer image exists. Use `deploy.sh`,
`docker compose pull` + `up -d`, or Watchtower instead.
