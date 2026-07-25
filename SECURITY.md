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
- `POST /api/slack/interactions` — the Slack interactivity callback, present
  only when actionable Slack approvals are configured. Unauthenticated at the
  *session* layer (Slack has no login), but the caller is authenticated
  **cryptographically**: every request must carry a valid `X-Slack-Signature`
  (HMAC-SHA256 over `v0:{timestamp}:{body}` using the app's signing secret),
  and requests older than 5 minutes are rejected as replays. Verification
  happens **before any state change**; an invalid or absent signature gets
  `401`. The path is per-IP rate-limited like the callout endpoint.
  A valid signature only proves the request came from your Slack workspace —
  **not** that the clicking user may approve. Authorization is separate and
  explicit: the Slack user id must appear in `SLACK_APPROVER_MAP`, and
  unmapped users are rejected and audited. Channel membership never grants
  decision rights. Leaving `SLACK_SIGNING_SECRET` unset disables the endpoint
  in practice (every request fails verification).
- `/actuator/health` — health probe.
- `/api/config/auth` — advertises which login methods are enabled (needed to
  render the login page).
- `/login` and static frontend assets.

**Everything else requires an authenticated session**, and — since roles landed
— an authenticated session with a sufficient **role**. An unauthenticated
request reaching any other `/api/**` endpoint would be a vulnerability, as
would a request succeeding for a role that should not be permitted it. Please
report either.

## Roles

Authorization is driven by Omnissa Access group membership (`OMNISSA_ROLE_MAP`,
matched against the OIDC `group_ids` claim):

| Role | Permitted |
|---|---|
| `ROLE_ADMIN` | Users, auto-approval rule writes, tenant config, log bundle, request deletion, remote purge — plus everything below |
| `ROLE_APPROVER` | Decisions: approve, reject, revoke, revoke-and-block, allow re-request, pull |
| `ROLE_VIEWER` | Read-only: queue, catalog, statistics, rules, audit |
| `ROLE_AUDITOR` | Audit trail and CSV export only — no live queue, no decisions |

`ROLE_USER` predates the model and is treated as `ROLE_VIEWER`.

Rules are enforced centrally in `SecurityConfig` rather than scattered across
`@PreAuthorize` annotations, so the whole policy is reviewable in one place.
Roles are **additive**; every signed-in user holds at least `ROLE_VIEWER`, and
with no role map configured that is *all* anyone holds — absence of
configuration is restrictive, not permissive.

Two properties are load-bearing and easy to break:

- Role mapping keys on group **ids**, not names, so renaming a group in Access
  cannot silently drop everyone to Viewer.
- The group claim is served by Omnissa Access as an **overflow claim** once a
  user is in roughly twenty or more groups: the ID token then carries `ovc` /
  `ovl` pointing at the userinfo endpoint instead of the values. Roles resolve
  correctly only because Spring's `OidcUserService` fetches and merges
  userinfo. Any change that reads the ID token alone would fail *open to
  Viewer* for exactly the users most likely to be administrators.

The UI hides controls a role cannot use, but that is convenience only — the
server is the boundary, and every rule is enforced there regardless of what the
SPA renders.

### Chat approvals and roles

**Teams decisions are subject to roles.** The card's buttons are deep links, so
the approver signs in and every rule applies as it does in the web UI.

**Slack decisions are not.** A Slack decision is made inside the interaction
callback, where there is no signed-in principal to check a role against — the
request is authenticated by *signature*, which proves it came from your
workspace, not who may act. Authorization comes solely from
`SLACK_APPROVER_MAP`, which is therefore a **second, independent source of
authority**: removing someone from an approver group in Omnissa Access revokes
their web access immediately but leaves their Slack buttons working until the
map is also updated and the container recreated. Keep the two in step.

### Chat notifications are readable by the whole channel

Notifications are posted to a Slack or Teams channel, so **every member of that
channel can read the request details** — application name, requester and timing
— regardless of role, and regardless of whether they have an account at all.
Roles govern who may *act*, never who may *see*. If a channel is broader than
the set of people who should know who is requesting what, that is settled
through channel membership; the tool cannot enforce it. Treat an approvals
channel as having the same audience as the request queue itself.

## Hardening Options

- `OMNISSA_API_USERNAME` / `OMNISSA_API_PASSWORD` — require HTTP Basic auth
  on the callout endpoint (set the same credentials in the Omnissa Access
  approvals settings).
- `OMNISSA_API_RATE_LIMIT` — per-IP requests/minute limit on the callout
  endpoint (default 60; `0` disables).
- `OMNISSA_AUTH_LOCAL_LOGIN_DISABLED=true` — disable local
  username/password login entirely (OAuth2-only admin login).
- `SLACK_APPROVER_MAP` — the authorization list for chat decisions. Keep it to
  users who should genuinely hold approval rights; everyone else in the channel
  can see the buttons but cannot act on them.
- Terminate TLS at a reverse proxy (Caddy/nginx) and keep the plain-HTTP
  port 8081 off the public internet. Only `POST /api/approvals/new` needs to
  be internet-reachable — plus `POST /api/slack/interactions` if actionable
  Slack approvals are enabled. Actionable **Teams** approvals need no inbound
  endpoint at all: the card's buttons are deep links, so only the approver's
  browser reaches the tool. See [docs/deployment.md](docs/deployment.md).

### Privileged operations

Some admin actions change entitlements in your Omnissa Access tenant, not just
local state — a permanent decline, *Revoke and block*, and *Allow re-request*
add or remove a per-user exclusion on the app. They require an authenticated
admin session, are confirmation-gated in the UI, and are recorded in the audit
trail with the acting identity. See
[docs/access-lifecycle.md](docs/access-lifecycle.md).

**Backup archives contain secrets** (OAuth client secret, Slack signing secret,
SMTP credentials). They are written `0600` inside a `0700` directory; treat a
copy of one as equivalent to the env file.

Also see the non-production disclaimer in [NOTICE.md](NOTICE.md).
