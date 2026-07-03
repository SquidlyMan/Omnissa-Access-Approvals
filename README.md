# Access Approval Tool for Omnissa

A self-hosted approval gateway for Omnissa Access (Workspace ONE): users request applications from the catalog, administrators approve or deny them in a live web queue, and decisions flow back to Omnissa Access automatically.

> **NOTICE:** This is an independent community project — **not an Omnissa product** and not affiliated with, endorsed by, or supported by Omnissa, LLC. It is provided **as-is, without warranty**, and is intended for **testing, lab, and demo use only — not production**. See [NOTICE.md](NOTICE.md).

[![CI](https://github.com/SquidlyMan/Omnissa-Access-Approvals/actions/workflows/ci.yml/badge.svg)](https://github.com/SquidlyMan/Omnissa-Access-Approvals/actions/workflows/ci.yml)
[![CodeQL](https://github.com/SquidlyMan/Omnissa-Access-Approvals/actions/workflows/codeql.yml/badge.svg)](https://github.com/SquidlyMan/Omnissa-Access-Approvals/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Container](https://img.shields.io/badge/ghcr.io-omnissa--access--approvals-2496ED?logo=docker&logoColor=white)](https://github.com/SquidlyMan/Omnissa-Access-Approvals/pkgs/container/omnissa-access-approvals)

When a user requests access to an application, Omnissa Access POSTs a callout to this service; administrators approve or deny the request through a web UI, and the decision is sent back via the Omnissa Access service client API.

## Features

- **Approval queue** with live updates (Server-Sent Events) and a Deactivated list
- **Native Omnissa Access callout integration** — messaging-envelope parsing, decision posting, connectivity status tile
- **Admin login**: local account and/or "Sign in with Omnissa Access" (OIDC + PKCE), optional OAuth-only mode, automatic consent-screen disable
- **Auto-approval rules** — wildcard app-name/group match rules and pending-expiry rules, first-match precedence
- **Audit trail** with admin identity, plus **CSV export**
- **Notifications** — SMTP email to requestors; webhooks in generic/Slack/Teams formats
- **Ops** — log bundle download, syslog export (UDP/TCP/TLS with client certs), health endpoint
- **API hardening** — optional Basic auth and per-IP rate limiting on the callout endpoint

## Quick Start

Pull the image (once published) and run it with an env file and a data volume:

```bash
docker pull ghcr.io/squidlyman/omnissa-access-approvals:latest

docker run -d --name omnissa-approvals \
  --env-file omnissa-approvals.env \
  -v ./data:/app/data \
  -p 8081:8081 \
  --restart unless-stopped \
  ghcr.io/squidlyman/omnissa-access-approvals:latest
```

Or build from source:

```bash
git clone https://github.com/SquidlyMan/Omnissa-Access-Approvals.git
cd Omnissa-Access-Approvals
docker build -t omnissa-access-approvals .
```

Use [`deploy/zimacube/omnissa-approvals.env.example`](deploy/zimacube/omnissa-approvals.env.example) as the env-file template, put a TLS reverse proxy in front (only `POST /api/approvals/new` must be internet-reachable), and configure your tenant — full walkthroughs in the docs:

## Documentation

- [Deployment](docs/deployment.md) — Docker/Compose, reverse-proxy requirements, inbound connectivity, ZimaCube
- [Configuration reference](docs/configuration.md) — every environment variable
- [Omnissa Access setup](docs/omnissa-access-setup.md) — OAuth clients, Settings > Approvals, per-app approval
- [Troubleshooting](docs/troubleshooting.md) — real failure modes and fixes
- [SECURITY.md](SECURITY.md) — reporting vulnerabilities, endpoint scope, hardening
- [CONTRIBUTING.md](CONTRIBUTING.md) — dev setup and PR guidelines
- [CHANGELOG.md](CHANGELOG.md) — release history

---

## Deployment Modes

Three Compose files are provided; pick one. Details in [docs/deployment.md](docs/deployment.md).

### Docker + Caddy (automatic HTTPS)

Caddy acts as a reverse proxy and obtains a TLS certificate automatically via Let's Encrypt. Requires a publicly reachable domain and open ports 80/443.

```bash
git clone https://github.com/SquidlyMan/Omnissa-Access-Approvals.git
cd Omnissa-Access-Approvals
cp .env.example .env    # fill in values — see docs/configuration.md
docker compose up -d
```

The application will be available at `https://<APPROVAL_DOMAIN>`.

### Standalone TLS Mode

Use this when you have your own certificate and can't use Let's Encrypt:

```bash
# Generate a keystore from PEM files:
bash scripts/import-cert.sh
# Or generate a self-signed cert for testing:
bash scripts/gen-dev-cert.sh

docker compose -f docker-compose-standalone.yml up -d
```

Set `SSL_KEYSTORE_PASSWORD` in `.env` before starting.

### Behind Your Own Reverse Proxy (nginx / NPM / CasaOS)

Use this mode when another proxy already owns ports 80/443 on the host:

```bash
cp .env.example .env    # APPROVAL_DOMAIN and SSL_KEYSTORE_PASSWORD are unused here
docker compose -f docker-compose-proxy.yml up -d --build
```

The app listens on plain HTTP port `8081`; point your proxy at it. The proxy **must** pass `X-Forwarded-Proto`/`X-Forwarded-Host` (the app uses them for `https://` OAuth2 redirect URIs — `server.forward-headers-strategy=framework` is set by default), and the SSE endpoint `/api/approvals/stream` needs `proxy_buffering off`. A complete nginx server block and Nginx Proxy Manager notes are in [docs/deployment.md](docs/deployment.md).

If nginx runs directly on the host, change the port mapping in `docker-compose-proxy.yml` to `"127.0.0.1:8081:8081"` so the unencrypted port is not reachable from the LAN; keep `"8081:8081"` if your proxy runs in a container.

### ZimaCube one-script deploy

For a ZimaCube specifically, `deploy/zimacube/` contains a complete, idempotent deployment: source and H2 data on `/media/ZIMARAID/omnissa-approvals/`, env file with `chmod 600`, a CasaOS-adoption-safe compose (pre-built image, all state bind-mounted), and a systemd unit that keeps port 8081 LAN-only via a `DOCKER-USER` iptables rule. On the NAS:

```bash
git clone https://github.com/SquidlyMan/Omnissa-Access-Approvals.git /media/ZIMARAID/omnissa-approvals/src
sudo sh /media/ZIMARAID/omnissa-approvals/src/deploy/zimacube/deploy.sh
# first run creates the env file and stops — edit it, then re-run
```

Then add the NPM proxy host `approvals.example.com` → `http://<nas-ip>:8081`. If NPM returns 502 and its access log shows requests arriving from a `172.x` Docker-bridge address, the LAN-only rule is dropping proxy traffic — insert an accept for the bridge network above the drop: `iptables -I DOCKER-USER -p tcp --dport 8081 -s 172.16.0.0/12 -j ACCEPT` (and add a matching `ExecStart` line to the systemd unit).

---

## Local Development

Uses an embedded H2 database and runs without Docker. Requires Java 17 (Node/npm are handled by `frontend-maven-plugin`).

```bash
cp config/application-local.properties.example config/application-local.properties
# fill in the required values, then:
./mvnw spring-boot:run -Dspring.config.additional-location=file:./config/ -Dspring.profiles.active=local
```

Open `http://localhost:8081`. To iterate on the frontend without a full Maven build, run the Vite dev server separately after the first Maven build has populated `src/main/resources/static/`:

```bash
cd src/main/frontend
npm install
npm run dev
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for PR guidelines.

---

## Configuration

All variables are set in `.env` for Docker deployments, or in `config/application-local.properties` for local development. The complete reference — service client, bootstrap admin, admin OAuth2 login, API security, mail, webhooks, syslog — lives in **[docs/configuration.md](docs/configuration.md)**, and [`deploy/zimacube/omnissa-approvals.env.example`](deploy/zimacube/omnissa-approvals.env.example) is a fully commented template.

## Omnissa Access Setup

Two OAuth clients are required in your tenant (a Client Credentials service client for the approvals API, and optionally an OIDC client for admin login), plus **Settings > Approvals** pointed at `https://<your-host>/api/approvals/new` and **License Approval Required** on each gated app. The full walkthrough is in **[docs/omnissa-access-setup.md](docs/omnissa-access-setup.md)**.

## First Login

- **Local admin (default)** — on first startup, if `OMNISSA_BOOTSTRAP_ADMIN_USERNAME` and `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` are set and the user table is empty, a local admin account is created automatically. Sign in with those credentials.
- **Omnissa Access OAuth2** — if the `OMNISSA_ADMIN_OAUTH_*` variables are configured, a **Sign in with Omnissa Access** button appears on the login page. Any user who authenticates successfully through that client is granted full admin access — restrict the client accordingly.

---

## Architecture

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.2.5, Spring Security |
| Database | H2 embedded (file-backed, survives restarts) |
| Frontend | React, TypeScript, Vite, Tailwind CSS |
| Live updates | Server-Sent Events (SSE) |
| Email | Spring Mail + FreeMarker templates |
| Reverse proxy | Caddy (bundled option) or your own (nginx/NPM), or embedded Tomcat TLS |

## License

[MIT](LICENSE) — Copyright (c) 2026 Dean Flaming. Trademark and non-production disclaimers in [NOTICE.md](NOTICE.md).
