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
- `/actuator/health` — liveness probe.
- `GET /api/health/deps` — dependency status for an external monitor. Returns
  **only** an aggregate word (`{"status":"UP"}` / `DEGRADED` / `DOWN`): no tenant
  hostname, no error strings, and deliberately **no counts** — a pending or
  drift number would leak request volume to anyone able to reach the URL. The
  detailed per-component view at `/api/health/dependencies` requires a session.
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
| `ROLE_VIEWER` | Read-only *on screen*: queue, catalog, statistics, rules, audit. No CSV export |
| `ROLE_AUDITOR` | Audit trail only, including its CSV export — no live queue, no decisions |

`ROLE_USER` predates the model and is treated as `ROLE_VIEWER`.

Rules are enforced centrally in `SecurityConfig` rather than scattered across
`@PreAuthorize` annotations, so the whole policy is reviewable in one place.

`ROLE_VIEWER` is a **fallback, not a floor**: a user whose groups match nothing
gets Viewer, but once any group matches, the matched roles are exactly what
they hold. Matched roles are additive among themselves. With no role map
configured every user is a Viewer — absence of configuration is restrictive,
not permissive.

`ROLE_AUDITOR` must not be combined with another role. Resolution is additive
and every rule admits any one of its listed roles, so the most permissive role
wins: an auditor who also holds Approver keeps full access to the live queue and
the auditor restriction has no effect. Alongside `ROLE_ADMIN` or
`ROLE_APPROVER` it is additionally a separation-of-duties conflict — one
identity deciding requests and auditing those decisions. The combination is
reported at each sign-in with a `WARN` naming the user and roles, rather than
silently corrected: a group membership that *removed* access would be
surprising in its own way, and the defect worth fixing was that the conflict
was invisible.

The distinction matters for `ROLE_AUDITOR`, which grants *less* than Viewer.
Granting Viewer unconditionally would make every role additive on top of it,
and since Viewer already includes reading the audit trail, an auditor would
hold Viewer's access to the live queue plus nothing extra — the opposite of the
role's purpose. A role that restricts cannot exist in a model where everyone
starts as a Viewer.

Bulk export is gated separately from reading, because an export is an
extraction rather than a read: it produces a file that leaves the application's
controls entirely and can be retained or shared without trace, whereas reading
the trail on screen is page-by-page and stays inside the session. So a Viewer
may read the audit trail but not download it. `/api/audit/export.csv` is
restricted to `ROLE_ADMIN` and `ROLE_AUDITOR`; `/api/approvals/export.csv` to
`ROLE_ADMIN`, `ROLE_APPROVER` and `ROLE_AUDITOR`.

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

**Both Slack and Teams decisions are subject to roles.** Neither posts a
callback: the buttons are deep links, so the approver opens the request in the
tool, signs in, and every authorization rule applies exactly as it does in the
web UI.

Slack approvals were previously interactive, and decided inside an inbound
callback where no signed-in principal existed — a Slack signature proves the
workspace, not the person. Authorization therefore came from a separate
`SLACK_APPROVER_MAP`, a second source of truth that failed **open**: removing
someone from an approver group in Omnissa Access revoked their access to the
web UI immediately but left their Slack buttons working, silently. Deep links
remove that divergence by construction, and with it the inbound endpoint, its
signing secret, its replay window and the approver list.

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

  Consider leaving local login **enabled** instead, as a deliberate
  break-glass: roles are resolved from Omnissa Access group membership, so an
  unreachable tenant, a misconfigured OIDC client or a wrong role map otherwise
  locks every administrator out, recoverable only by editing the container's
  environment and recreating it. If you keep it, treat the local admin as a
  standing credential — give it a long random password and **rotate it from the
  Users page**, since `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` only applies on first
  run and silently does nothing afterwards.

  The tool refuses to disable, delete or demote the last enabled local
  administrator, so the break-glass cannot be removed by accident.
- Terminate TLS at a reverse proxy (Caddy/nginx) and keep the plain-HTTP
  port 8081 off the public internet. **Only `POST /api/approvals/new` needs to
  be internet-reachable.** Chat approvals add no inbound endpoint at all — both
  Slack and Teams use deep links, so only the approver's browser reaches the
  tool. See [docs/deployment.md](docs/deployment.md).

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
