# Configuration Reference

All configuration is via environment variables. Unless a page says otherwise,
**every variable in this document is set in the tool's env file**:

- `omnissa-approvals.env` — the ZimaCube/Docker deployment (referenced by
  `env_file:` in the Compose file; on a ZimaCube it lives at
  `/media/ZIMARAID/omnissa-approvals/omnissa-approvals.env`);
- `.env` — the bundled Compose files in this repository;
- or as container environment values, if your platform manages them that way;
- or as properties in `config/application-local.properties` for local
  development.

Changes require a container **recreate** (`docker compose … up -d
--force-recreate`) — a plain restart does not re-read the env file.

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

> **These variables cannot rotate the password.** The bootstrap runs only when
> the user table is empty, so on an existing install changing
> `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` has **no effect at all** — and does so
> silently. Rotate it in the app instead: **Users** page → *Reset password*, or
> **Change my password** for your own account.

Because local sign-in is the **break-glass** route — roles come from Omnissa
Access group membership, so a local admin is the only way in when the tenant is
unreachable or the role map is wrong — the tool refuses to disable, delete or
demote the last enabled local administrator. Grant a second account the Admin
role first if you need to remove one.


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
| `OMNISSA_ADMIN_OAUTH_SCOPE` | `openid,email,profile,group` | Scopes requested at sign-in. The **`group`** scope is what makes Access emit the `group_names` / `group_ids` claims that [roles](#roles-rbac) are resolved from. Override only if your tenant does not advertise it in `scopes_supported` — the tool works without it, users simply have no group claim to map |

> **Issuer warning:** the most common OIDC failure is setting the issuer to
> anything other than `https://<tenant>/SAAS/auth`. If the issuer in the
> tenant's discovery document does not match this value exactly, login fails
> at startup or with an `invalid_id_token` error.

**Optional means optional.** With `OMNISSA_ADMIN_OAUTH_CLIENT_ID` blank — the
shipped default — no OAuth2 client is registered at all: nothing contacts a
tenant at startup, `/oauth2/**` is not in the filter chain, and the sign-in
page offers only the local form.

A client id set *without* either `OMNISSA_ADMIN_OAUTH_ISSUER_URI` or manually
configured `spring.security.oauth2.client.provider.omnissa.*` endpoints is a
half-configuration. The tool starts anyway on local sign-in, logs an **error**
naming which of the two to set, and keeps the OAuth2 button hidden rather than
offering one that leads nowhere. Grep the log for `Admin OAuth2 login` to see
which state you are in.

## Authentication Options

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_AUTH_LOCAL_LOGIN_DISABLED` | `false` | `true` hides the local username/password form entirely — OAuth2-only admin sign-in. Requires a working `OMNISSA_ADMIN_OAUTH_*` setup |

## Roles (RBAC)

Authorization is driven by **Omnissa Access group membership**. See
[Roles](../README.md#roles) for the full model.

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_ROLE_MAP` | — | Comma-separated `<groupId>:<ROLE>` pairs mapping Access groups to roles. Roles: `ADMIN`, `APPROVER`, `VIEWER`, `AUDITOR`. Blank = **every** signed-in user is a Viewer |

```bash
OMNISSA_ROLE_MAP=05eb7969-…:ADMIN,63173f00-…:APPROVER,4378e8f5-…:AUDITOR
```

| Role | Can |
|---|---|
| `ADMIN` | Users, auto-approval rules, tenant config, log bundle, delete requests — plus everything below |
| `APPROVER` | Decide requests: approve, reject, revoke, revoke-and-block, allow re-request |
| `VIEWER` | Read the queue, request details, statistics, rules and the audit trail |
| `AUDITOR` | The audit trail and its CSV export only — no live queue, no decisions |

Things that bite in practice:

- **It matches group *ids*, not names.** Renaming a group in Access would
  otherwise silently drop everyone to Viewer with no error anywhere. Sign in and
  open **`/api/auth/claims`** to read the ids — it pairs each id with its
  display name.
- **Requires the `group` scope** (`OMNISSA_ADMIN_OAUTH_SCOPE`, requested by
  default). Without it Access emits no group claim at all.
- **Viewer is a fallback, not a floor.** A user whose groups match nothing gets
  Viewer; once any group matches, the matched roles are exactly what they hold.
  With no map configured every user is a Viewer, so setting the map is the
  deliberate act that grants privilege.
- **Matched roles are additive, and the most permissive wins.**
- **Changes apply at next sign-in.** Roles come from the token, which is a
  snapshot — adding or removing a group does nothing until the user signs out
  and back in.
- **Do not combine `AUDITOR` with another role.** It is the only restrictive
  role, so pairing it with any other silently defeats it; alongside `ADMIN` or
  `APPROVER` it is also a separation-of-duties conflict. The tool logs a `WARN`
  at sign-in when it sees this.
- **Use groups created for this purpose.** Mapping an existing operational group
  means anyone added to it for unrelated reasons silently gains the ability to
  revoke and block entitlements in your tenant.
- **Keep a way back in.** If the mapping is wrong or Access is unreachable, set
  `OMNISSA_AUTH_LOCAL_LOGIN_DISABLED=false` and sign in with the bootstrap admin.

Bulk export is gated separately from reading, because an export is an
extraction rather than a read — it produces a file that leaves the tool's
controls entirely. `/api/audit/export.csv` is `ADMIN` + `AUDITOR`;
`/api/approvals/export.csv` is `ADMIN` + `APPROVER` + `AUDITOR`. A Viewer may
read the audit trail on screen but not download it.

## Local Accounts

Local sign-in is the **break-glass** route — see the note under
[First-Run Local Admin](#first-run-local-admin-bootstrap). Accounts are managed
in the app rather than by configuration:

| Action | Where | Who |
|---|---|---|
| Change your own password | **Change my password**, top bar | any account signed in locally |
| Add an account | **Users** page | Admin |
| Reset a password | **Users** page | Admin |
| Enable / disable | **Users** page | Admin |
| Change roles | **Users** page | Admin |
| Delete | **Users** page | Admin |

All are recorded in the audit trail. The corresponding endpoints are
`PUT /api/users/me/password` (any local user) and, under `/api/users/{id}`,
`password` / `enabled` / `roles` plus `DELETE` (Admin only).

**Disabling, deleting or demoting the last enabled local administrator is
refused** with HTTP 409 and an explanation. An Omnissa Access user holding the
Admin role through a group does *not* satisfy the guard, because the situations
break-glass exists for are exactly those where Access sign-in is unavailable.

### Password rules

| Variable | Default | Purpose |
|---|---|---|
| `OMNISSA_PASSWORD_MIN_LENGTH` | `12` | Minimum length. **Clamped to a floor of 8** — configuration may tighten this policy, not remove it. A lower value is raised and a warning logged |
| `OMNISSA_PASSWORD_MIN_DISTINCT` | `5` | Distinct characters required, so `aaaaaaaaaaaa` is not merely "long" |
| `OMNISSA_PASSWORD_BLOCK_USERNAME` | `true` | Reject a password containing the username |
| `OMNISSA_PASSWORD_BLOCKLIST_FILE` | — | Extra wordlist, one entry per line, merged with the bundled list |
| `OMNISSA_PASSWORD_REQUIRE_MIXED_CASE` | `false` | Composition rule — see below |
| `OMNISSA_PASSWORD_REQUIRE_DIGIT` | `false` | Composition rule |
| `OMNISSA_PASSWORD_REQUIRE_SYMBOL` | `false` | Composition rule |

**Composition rules are off by default and are not recommended.** They are
discouraged (NIST SP 800-63B) because people decorate rather than abandon a weak
password — `password` becomes `Password1!`, which satisfies every rule while
being exactly what a cracking toolchain generates first. They also reject strong
passphrases: `correct horse battery staple` fails a digit-and-symbol
requirement. They are provided for compliance requirements, not because they
help.

#### Why the bundled blocklist is small, and when to replace it

A general common-password corpus is nearly useless at a 12-character minimum:
of the **10,000 most common passwords, only 10 reach 12 characters** — the
length rule alone rejects the other 9,990. Bundling such a list would add weight
while implying protection it does not give.

The bundled list therefore targets the gap that actually exists: values long
enough to pass the minimum yet still trivially guessable — doubled words
(`passwordpassword`), keyboard walks (`qwertyuiopasdf`), digit runs and stock
phrases. Note these also defeat composition rules: `Passwordpassword1!`
satisfies every character-class requirement.

**That calculus reverses if you lower `OMNISSA_PASSWORD_MIN_LENGTH`.** At 8
characters the whole corpus is back in scope, and you should point
`OMNISSA_PASSWORD_BLOCKLIST_FILE` at a real wordlist — for example SecLists'
`10k-most-common.txt` mounted into `/app/data`. The entry count is logged at
startup, so a mistyped path is visible rather than silently leaving you with the
bundled list.

### Sign-in throttling

Repeated failed local sign-ins are slowed progressively — roughly a doubling
delay after three failures, capped so a request thread is never held long — and
an address making sustained attempts is refused outright with HTTP 429.

**There is deliberately no account lockout.** Locking an account after N
failures would let anyone who can reach the login page disable the break-glass
credential at will, precisely when Omnissa Access is unavailable and it is the
only way in. So the per-address counter may refuse, but the per-username counter
only ever delays: an attacker spread across many addresses cannot lock out the
real administrator. Counters expire on their own and clear on a successful
sign-in; nothing needs resetting by hand.

## Callout API Security

| Variable | Default | Description |
|---|---|---|
| `OMNISSA_API_USERNAME` | — | HTTP Basic auth on `POST /api/approvals/new`. Configure the same username/password in the Access console under **Settings > Approvals**. **Required once `OMNISSA_BOOTSTRAP_URL` is set** — the application refuses to start without either this or `OMNISSA_API_ALLOW_UNAUTHENTICATED`. `OPTIONS` probes always remain unauthenticated, so saving the Access settings still works |
| `OMNISSA_API_PASSWORD` | — | Password paired with `OMNISSA_API_USERNAME` |
| `OMNISSA_API_ALLOW_UNAUTHENTICATED` | `false` | Accept unauthenticated callouts on a tenant-configured install. Only appropriate where the endpoint genuinely cannot be reached from anywhere untrusted; a warning is logged hourly while it is set |
| `OMNISSA_API_RATE_LIMIT` | `60` | Maximum callout requests per minute per client address on `/api/approvals/new`; excess requests receive HTTP 429. `0` disables rate limiting |
| `OMNISSA_SECURITY_TRUSTED_PROXY_HOPS` | `0` | How many reverse proxies sit in front of the container. Decides which `X-Forwarded-For` entry is believed when keying rate limits and the login throttle — see [Client addresses behind a proxy](#client-addresses-behind-a-proxy) |

> ### ⚠️ The callout endpoint will not start unauthenticated
>
> Once `OMNISSA_BOOTSTRAP_URL` names a tenant, the application refuses to start
> unless `OMNISSA_API_USERNAME` is set or `OMNISSA_API_ALLOW_UNAUTHENTICATED=true`.
>
> `POST /api/approvals/new` is the one path that has to face the internet,
> because the Access cloud does the POSTing. Left open, anything that reaches
> the URL can place requests in the queue that look exactly like real ones — and
> approving one grants a real entitlement. Previously the credentials were
> optional and blank by default, so the shipped configuration was the open one
> and nothing said so.
>
> **Before a tenant is configured nothing is demanded**, so a first run still
> needs no configuration at all: stand the container up, confirm it serves, then
> point it at Access. With no tenant there is nothing an injected request could
> reach.

### Client addresses behind a proxy

Rate limiting and the login throttle are keyed on the client address. Reverse
proxies **append** to `X-Forwarded-For`, so the header reads
`client, proxy1, proxy2` — and the **leftmost entry is written by the caller**.
Trusting it lets anyone pick their own bucket by varying a header, which removes
both the callout rate limit and the brute-force protection on the local admin
password.

So the count is taken **from the right**:

| `OMNISSA_SECURITY_TRUSTED_PROXY_HOPS` | Which address is used |
|---|---|
| `0` *(default)* | The socket peer. Nothing in the header is believed |
| `1` | The entry your nearest proxy wrote |
| `2` | One hop further out |

The default is safe everywhere and degrades honestly: behind an unconfigured
proxy every request keys to the proxy's own address, so limits are *shared*
rather than forgeable. Shared limits are a nuisance; forgeable limits are not
limits.

**Do not guess the number.** On the first request carrying `X-Forwarded-For`
the application logs the chain it received and the address it selected:

```
First forwarded request seen. X-Forwarded-For carried 2 entries: [203.0.113.9, 10.88.88.7].
With omnissa.security.trusted-proxy-hops=0 the client is recorded as 10.88.88.1.
```

Count the entries in that line. If a request that traverses fewer proxies than
configured arrives — someone reaching the container directly — the header is
ignored for that request and the socket peer is used instead.

## Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8081` | HTTP listen port of the application |

## Email Notifications (SMTP)

Outbound mail for approval decision notifications to requestors. Blank host
= disabled: no mail sender is created, health is unaffected, and decisions
work exactly as before — each one logs a **warning** naming
`spring.mail.host` instead of e-mailing the requester. Nothing fails
silently, so a decision that looks successful never leaves you assuming a
notification went out that did not.

| Variable | Default | Description |
|---|---|---|
| `SPRING_MAIL_HOST` | — | SMTP server hostname |
| `SPRING_MAIL_PORT` | `587` | SMTP port |
| `SPRING_MAIL_USERNAME` | — | SMTP authentication username |
| `SPRING_MAIL_PASSWORD` | — | SMTP authentication password |
| `SPRING_MAIL_FROM` | `no-reply@example.com` | Sender (From) address for requester emails — must be an address the relay accepts (Office 365: an accepted-domain address matching the sending account) |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `false` | `true` for SMTP servers requiring STARTTLS (e.g. Gmail on 587) |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT` | `10000` | Connect timeout, milliseconds |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT` | `10000` | Read timeout, milliseconds |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_WRITETIMEOUT` | `10000` | Write timeout, milliseconds |

Mail is sent synchronously, and Jakarta Mail's own default for all three
timeouts is **infinite** — so leave them set. A relay that silently drops
packets rather than refusing the connection, which is what a firewalled port 25
does, would otherwise hold the sending thread until the process restarts. Raise
them if a slow relay is timing out legitimately; do not remove them.

## Webhook Notifications

POSTs a notification whenever a new activation request arrives and whenever
a request is decided (approved or rejected — by an admin or by an
auto-approval rule). Fire-and-forget: delivery failures are logged as WARN
and never block request ingestion or decisions.

| Variable | Default | Description |
|---|---|---|
| `WEBHOOK_URL` | — | Webhook URL to POST to on each new request and each decision. Blank = disabled |
| `WEBHOOK_FORMAT` | `generic` | Payload format: `generic`, `slack`, or `teams` |
| `WEBHOOK_NOTIFY_LIFECYCLE` | `true` | Also notify on JIT lifecycle events: **`access.revoked`** when a time-bound grant expires, and **`access.reopened`** when a re-requestable app becomes requestable again. `false` = those events stay in the audit trail and web UI only |

The three formats:

- **`generic`** — a plain JSON event, for n8n, Zapier catch hooks, custom
  scripts, or webhook.site testing. New request (`request.created`):

  ```json
  {"event":"request.created","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","operation":"activation","receivedDate":"2026-07-03T08:11:43Z"}
  ```

  Decision (`request.decided`) — an admin decision:

  ```json
  {"event":"request.decided","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","decision":"approved","decidedBy":"dean","decidedDate":"2026-07-03T18:00:00Z"}
  ```

  and an auto-rule decision (`decidedBy` is the literal
  `auto-approval-rule`, and `rule` carries the rule number):

  ```json
  {"event":"request.decided","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","decision":"rejected","decidedBy":"auto-approval-rule","rule":"#7","decidedDate":"2026-07-03T18:00:00Z"}
  ```

- **`slack`** — Slack Incoming Webhook payload (create one under your Slack
  app's **Incoming Webhooks**; URL looks like
  `https://hooks.slack.com/services/T…/B…/…`). New request:

  ```json
  {"text":"New access request: Example App (SAML) requested by user 123456 — approve or reject in the Access Approval Tool."}
  ```

  Decisions — admin and auto-rule:

  ```json
  {"text":"Approved by dean: Example App (SAML) (user 123456)"}
  ```

  ```json
  {"text":"Auto-Rejected by rule #5: Example App (SAML) (user 123456)"}
  ```

- **`teams`** — same `text` payloads for a Microsoft Teams channel workflow.
  Create it from the **Workflows** app using the *"Send Webhook Alerts to a
  Channel"* template, then **Copy webhook link** — the URL is on
  `*.environment.api.powerplatform.com`. (The older `webhook.office.com`
  connector URLs belong to Office 365 connectors, which Microsoft has retired.)

### Actionable Teams approvals

With `WEBHOOK_FORMAT=teams`, the new-request notification can be an **Adaptive
Card** whose Approve/Reject buttons open the request in this tool with that
decision pre-selected. Full walkthrough:
[Actionable Teams Approvals](teams-approvals.md).

| Variable | Default | Purpose |
|---|---|---|
| `TEAMS_ACTIONABLE` | `false` | `true` posts the Adaptive Card instead of plain text (requires `WEBHOOK_FORMAT=teams` **and** `APP_BASE_URL`) |
| `APP_BASE_URL` | — | Public URL of this tool, e.g. `https://approvals.example.com`. Required to build the card's deep links — notifications are sent from a background thread, so the public URL cannot be read from forwarded headers. Blank = fall back to plain text rather than emit broken links |

The buttons are deep links, not callbacks: Office 365 connectors (which
supported `Action.Http`) are retired, and a Power Automate callback would need
the **premium** HTTP connector. Approvers authenticate with this tool's own
login, so no inbound endpoint, shared secret, or approver map is needed.

### Actionable Slack approvals

With `WEBHOOK_FORMAT=slack`, the new-request notification can be an
**interactive** message — an access-duration menu plus Approve/Reject buttons —
so approvers decide from Slack. Full walkthrough:
[Actionable Slack Approvals](slack-approvals.md).

| Variable | Default | Purpose |
|---|---|---|
| `SLACK_ACTIONABLE` | `false` | `true` adds Approve / Reject / Open buttons to the message (requires `WEBHOOK_FORMAT=slack` and `APP_BASE_URL`) |
| `APP_BASE_URL` | — | **Required** for the buttons — the public URL used to build their deep links |

The buttons are **deep links**, not interaction callbacks: they open the request
in this tool, so the approver signs in and their
[role](../README.md#roles) decides what they may do. There is **no inbound
endpoint, no signing secret and no separate approver list**.

That last point is the reason for the design. A callback arrives where no
signed-in user exists — a Slack signature proves the workspace, not the person —
so authorization had to come from a separate map, which drifted from Omnissa
Access group membership and failed *open*.

If `APP_BASE_URL` is blank the tool sends the plain-text notification rather
than emitting dead links.

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
