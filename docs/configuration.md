# Configuration Reference

All configuration is via environment variables (set in your `.env` /
env file for Docker, or as properties in
`config/application-local.properties` for local development). Changes
require a container recreate/restart to apply.

A complete, commented env-file template lives at
[`deploy/zimacube/omnissa-approvals.env.example`](../deploy/zimacube/omnissa-approvals.env.example).

## Omnissa Access Service Client (required)

The service client is used to post approval decisions back to Omnissa Access
and for the dashboard connectivity check. Create it as a **Service Client**
with the **Client Credentials** grant — see
[Omnissa Access setup](omnissa-access-setup.md).

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_BOOTSTRAP_URL` | — | Tenant hostname, no scheme (e.g. `tenant.us1.wss.workspaceone.com`) |
| `OMNISSA_BOOTSTRAP_CLIENT_ID` | — | Service client ID (e.g. `ApprovalService`) |
| `OMNISSA_BOOTSTRAP_CLIENT_SECRET` | — | Service client secret |

## First-Run Local Admin (bootstrap)

Creates a local administrator account on first startup, **only when the user
table is empty**. Ignored on subsequent starts.

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_BOOTSTRAP_ADMIN_USERNAME` | — | Username of the initial local admin |
| `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` | — | Password of the initial local admin |
| `OMNISSA_BOOTSTRAP_ADMIN_EMAIL` | — | Email address of the initial local admin (optional) |

## Admin OAuth2 Login (optional)

Enables "Sign in with Omnissa Access" (OIDC) for administrators. If omitted,
only local login is available. **Any user who authenticates successfully
through this client is granted full admin access** — restrict who can use
the client in the Access console.

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_ADMIN_OAUTH_CLIENT_ID` | — | OIDC client ID from Omnissa Access (e.g. `ApprovalAdmin`) |
| `OMNISSA_ADMIN_OAUTH_CLIENT_SECRET` | — | Secret of that client |
| `OMNISSA_ADMIN_OAUTH_REDIRECT_URI` | — | Must **exactly** match the redirect URI registered on the Access client: `https://<your-host>/login/oauth2/code/omnissa` (public hostname, not the backend host/port) |
| `OMNISSA_ADMIN_OAUTH_ISSUER_URI` | — | Tenant OIDC issuer: `https://<tenant>/SAAS/auth` — the `issuer` value from `/.well-known/openid-configuration`. **Not** `/SAAS/auth/acs` or any other path |
| `OMNISSA_ADMIN_OAUTH_DISABLE_CONSENT` | `false` | `true` = at startup, automatically disable the user-consent prompt on the OIDC client via the Access admin API (requires the service client to have admin rights). Set it after confirming OAuth2 login works |

> **Issuer warning:** the most common OIDC failure is setting the issuer to
> anything other than `https://<tenant>/SAAS/auth`. If the issuer in the
> tenant's discovery document does not match this value exactly, login fails
> at startup or with an `invalid_id_token` error.

## Authentication Options

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_AUTH_LOCAL_LOGIN_DISABLED` | `false` | `true` hides the local username/password form entirely — OAuth2-only admin sign-in. Requires a working `OMNISSA_ADMIN_OAUTH_*` setup |

## Callout API Security

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_API_USERNAME` | — | When set, `POST /api/approvals/new` requires HTTP Basic auth with these credentials. Configure the same username/password in the Access console under **Settings > Approvals**. Blank = open endpoint. `OPTIONS` probes always remain unauthenticated |
| `OMNISSA_API_PASSWORD` | — | Password paired with `OMNISSA_API_USERNAME` |
| `OMNISSA_API_RATE_LIMIT` | `60` | Maximum callout requests per minute per source IP on `/api/approvals/new`; excess requests receive HTTP 429. `0` disables rate limiting |

## Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8081` | HTTP listen port of the application |

## Email Notifications (SMTP)

Outbound mail for approval decision notifications to requestors. Blank host
= disabled.

| Variable | Default | Description |
|---|---|---|
| `SPRING_MAIL_HOST` | — | SMTP server hostname |
| `SPRING_MAIL_PORT` | `587` | SMTP port |
| `SPRING_MAIL_USERNAME` | — | SMTP authentication username |
| `SPRING_MAIL_PASSWORD` | — | SMTP authentication password |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `false` | `true` for SMTP servers requiring STARTTLS (e.g. Gmail on 587) |

## Webhook Notifications

POSTs a notification whenever a new activation request arrives.
Fire-and-forget: delivery failures are logged as WARN and never block
request ingestion.

| Variable | Default | Description |
|---|---|---|
| `WEBHOOK_URL` | — | Webhook URL to POST to on each new request. Blank = disabled |
| `WEBHOOK_FORMAT` | `generic` | Payload format: `generic`, `slack`, or `teams` |

The three formats:

- **`generic`** — a plain JSON event, for n8n, Zapier catch hooks, custom
  scripts, or webhook.site testing:

  ```json
  {"event":"request.created","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","operation":"activation","receivedDate":"2026-07-03T08:11:43Z"}
  ```

- **`slack`** — Slack Incoming Webhook payload (create one under your Slack
  app's **Incoming Webhooks**; URL looks like
  `https://hooks.slack.com/services/T…/B…/…`):

  ```json
  {"text":"New access request: Example App (SAML) requested by user 123456 — approve or reject in the Access Approval Tool."}
  ```

- **`teams`** — same `text` payload for a Microsoft Teams channel workflow
  ("Post to a channel when a webhook request is received"; URL on
  `webhook.office.com`).

Example:

```bash
WEBHOOK_URL=https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX
WEBHOOK_FORMAT=slack
```

## Syslog Export

Forwards all application logs — including the `AUDIT` logger — to a syslog
server. Blank host = disabled. Delivery never blocks the app: on connection
failure, events are dropped and reconnection is attempted on the next event.

| Variable | Default | Description |
|---|---|---|
| `SYSLOG_HOST` | — | Syslog server to forward logs to |
| `SYSLOG_PORT` | `514` | Port **number** only (e.g. `514`, `6514`) — the transport is chosen by `SYSLOG_PROTOCOL`, not here |
| `SYSLOG_PROTOCOL` | `udp` | Transport: `udp`, `tcp`, or `tls` (case-insensitive). Unknown values log a warning and fall back to `udp`. TCP/TLS use newline-delimited RFC 3164 framing (accepted by rsyslog and Graylog) |

### Syslog TLS options (only used when `SYSLOG_PROTOCOL=tls`)

Client cert + key enable mutual TLS — set **both or neither**. The `*_FILE`
variants are container paths and take precedence over the inline `*_PEM`
variants; put the files under `/app/data/certs/` (the persistent volume) —
easier than pasting multiline PEM into an env value.

| Variable | Default | Description |
|---|---|---|
| `SYSLOG_CA_PEM` | — | Inline PEM CA bundle used to verify the syslog server (private/self-signed CAs). Blank = platform default trust store |
| `SYSLOG_CLIENT_CERT_PEM` | — | Inline PEM client certificate (chain) for mutual TLS |
| `SYSLOG_CLIENT_KEY_PEM` | — | Inline PEM client private key |
| `SYSLOG_CA_FILE` | — | File-path variant of `SYSLOG_CA_PEM`, e.g. `/app/data/certs/syslog-ca.crt` |
| `SYSLOG_CLIENT_CERT_FILE` | — | File-path variant of `SYSLOG_CLIENT_CERT_PEM` |
| `SYSLOG_CLIENT_KEY_FILE` | — | File-path variant of `SYSLOG_CLIENT_KEY_PEM` |

> **PKCS#8 note:** the client private key must be **unencrypted PKCS#8**
> (`-----BEGIN PRIVATE KEY-----`). Convert a legacy PKCS#1/SEC1 key with
> `openssl pkcs8 -topk8 -nocrypt`.

Example:

```bash
SYSLOG_HOST=syslog.example.com
SYSLOG_PORT=6514
SYSLOG_PROTOCOL=tls
SYSLOG_CLIENT_CERT_FILE=/app/data/certs/client.pem
SYSLOG_CLIENT_KEY_FILE=/app/data/certs/client-key.pem
```

## Deployment-Mode Variables

Used only by the bundled Compose files, not by the app itself:

| Variable | Mode | Description |
|---|---|---|
| `APPROVAL_DOMAIN` | Caddy (`docker-compose.yml`) | Public domain name for automatic Let's Encrypt TLS |
| `SSL_KEYSTORE_PASSWORD` | Standalone (`docker-compose-standalone.yml`) | Password for the PKCS12 keystore |
