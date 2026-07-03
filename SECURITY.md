# Security Policy

## Supported Versions

This is a best-effort community project. Security fixes are applied to the
`main` branch and included in the latest release only. Older releases are not
patched — always run the latest version.

| Version | Supported |
|---|---|
| `main` / latest release | Yes |
| Older releases | No |

## Reporting a Vulnerability

Please report vulnerabilities privately using **GitHub private vulnerability
reporting**: open the repository's **Security** tab and click
**"Report a vulnerability"**.

Please do **not** open a public issue for security problems, and do not
include exploit details in public discussions until a fix is available.

### What to expect

This project is maintained on a best-effort basis by a community maintainer.
There is no security team and no guaranteed response SLA. Reports are
typically acknowledged within a couple of weeks; fixes depend on severity and
maintainer availability. Coordinated disclosure is appreciated.

## Scope Notes

The application is an admin-facing web UI plus a small public callout API.
The **only intentionally unauthenticated endpoints** are:

- `POST /api/approvals/new` and `OPTIONS /api/approvals/new` — the Omnissa
  Access callout endpoint. It is per-IP rate-limited (HTTP 429 on excess) and
  can additionally require HTTP Basic auth (see hardening below). The
  `OPTIONS` probe always remains unauthenticated so the Omnissa Access
  console can validate the URI.
- `/actuator/health` — health probe.
- `/api/config/auth` — advertises which login methods are enabled (needed to
  render the login page).
- `/login` and static frontend assets.

**Everything else requires an authenticated admin session.** An
unauthenticated request reaching any other `/api/**` endpoint would be a
vulnerability — please report it.

## Hardening Options

- `OMNISSA_API_USERNAME` / `OMNISSA_API_PASSWORD` — require HTTP Basic auth
  on the callout endpoint (set the same credentials in the Omnissa Access
  approvals settings).
- `OMNISSA_API_RATE_LIMIT` — per-IP requests/minute limit on the callout
  endpoint (default 60; `0` disables).
- `OMNISSA_AUTH_LOCAL_LOGIN_DISABLED=true` — disable local
  username/password login entirely (OAuth2-only admin login).
- Terminate TLS at a reverse proxy (Caddy/nginx) and keep the plain-HTTP
  port 8081 off the public internet; only `POST /api/approvals/new` needs to
  be internet-reachable. See [docs/deployment.md](docs/deployment.md).

Also see the non-production disclaimer in [NOTICE.md](NOTICE.md).
