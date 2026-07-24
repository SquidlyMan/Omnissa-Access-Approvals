# Actionable Slack Approvals

Post each new access request to Slack as an **interactive message** — an access
duration menu plus **Approve** / **Reject** buttons — and let approvers decide
without opening the web UI. The decision runs through exactly the same path as a
UI decision (delivery to Omnissa Access, JIT grant/TTL, audit trail), and the
Slack message is updated in place with the outcome.

> Optional feature, **disabled by default**. Without `SLACK_ACTIONABLE=true` the
> tool keeps sending the plain-text Slack notification.

---

## How it works

1. A request arrives → the tool posts a Block Kit message to your Slack
   **incoming webhook**: app name, requester, a duration menu, and two buttons.
2. An approver picks a duration and clicks **Approve** or **Reject**.
3. Slack POSTs the interaction to `POST /api/slack/interactions` on your host.
4. The tool **verifies Slack's signature**, maps the clicking Slack user to an
   authorized approver, applies the decision, and **replaces the message** with
   the result (e.g. *"✅ Approved by dean@flaming.ws · time-bound 60 min"*).

### Security model

The endpoint is unauthenticated at the session layer (Slack has no login), so
the caller is authenticated **cryptographically** and authorization is explicit:

- **Signature check** — every request must carry a valid `X-Slack-Signature`
  (HMAC-SHA256 over `v0:{timestamp}:{body}` using the app's *signing secret*).
  Requests older than 5 minutes are rejected (replay guard). Unsigned or
  tampered requests get `401` and never reach any state change.
- **Explicit approver mapping** — a valid signature only proves the click came
  from your Slack workspace, **not** that the clicker may approve. The Slack user
  id must appear in `SLACK_APPROVER_MAP`; anyone else gets *"You are not an
  authorized approver"* and the attempt is audited. **Channel membership never
  grants approval rights.**
- **Rate limited** — the path is registered with the same per-IP rate limiter as
  the Access callout endpoint.
- **Audited** — the decision is recorded with the resolved identity, e.g.
  `dean@flaming.ws (via Slack)`.

---

## 1. Create the Slack app

At <https://api.slack.com/apps> → **Create an App**.

### Option A — From a manifest (fastest)

Choose **From a Manifest**, pick your workspace, and paste (replacing the host):

```yaml
display_information:
  name: Access Approvals
  description: Approve or reject Omnissa Access app requests from Slack
  background_color: "#132250"
features:
  bot_user:
    display_name: Access Approvals
    always_online: false
oauth_config:
  scopes:
    bot:
      - incoming-webhook
settings:
  interactivity:
    is_enabled: true
    request_url: https://<your-host>/api/slack/interactions
  org_deploy_enabled: false
  socket_mode_enabled: false
  token_rotation_enabled: false
```

Then **Create** → **Install to Workspace** → choose the channel that should
receive approvals → **Allow**.

### Option B — Blank app (click-through)

1. **Create an App → Blank App**, name it, pick the workspace.
2. **Incoming Webhooks** → toggle **On** → *Add New Webhook to Workspace* →
   choose the channel → **Allow**.
3. **Interactivity & Shortcuts** → toggle **On** → **Request URL**:
   `https://<your-host>/api/slack/interactions` → **Save**.

### Collect three values

| Value | Where |
|---|---|
| **Webhook URL** | *Incoming Webhooks* → *Webhook URLs for Your Workspace* (`https://hooks.slack.com/services/…`) |
| **Signing secret** | *Basic Information* → *App Credentials* → **Signing Secret** → *Show* |
| **Approver member id(s)** | Slack profile → **⋮** → *Copy member ID* (`U…`) for each approver |

No bot token, OAuth scopes beyond `incoming-webhook`, or **app-level token** is
needed — app-level tokens are only for Socket Mode, which this feature does not
use.

---

## 2. Configure the tool

| Variable | Example | Purpose |
|---|---|---|
| `WEBHOOK_URL` | `https://hooks.slack.com/services/T…/B…/…` | Where messages are posted |
| `WEBHOOK_FORMAT` | `slack` | Required for Slack formatting |
| `SLACK_ACTIONABLE` | `true` | Turns the plain notification into the interactive message |
| `SLACK_SIGNING_SECRET` | *(32-char secret)* | Verifies inbound interactions — **required** |
| `SLACK_APPROVER_MAP` | `U0123ABC:dean@example.com,U0456DEF:jane` | Comma-separated `slackUserId:appIdentity` pairs |

`WEBHOOK_URL` carries **all** tool notifications (new requests, decisions, and
undeliverable notices), so they all land in the chosen channel.

Restarting is not enough for env changes — **recreate** the container:

```bash
docker compose -f <compose file> up -d --force-recreate
```

---

## 3. Expose the callback endpoint

Slack calls `POST /api/slack/interactions` from the internet, so the path must be
reachable through your reverse proxy — the same treatment as
`/api/approvals/new`:

- Publicly resolvable host, valid TLS certificate, port 443 open.
- **Behind a UAG:** add `/api/slack/interactions` to the **proxyPattern**
  whitelist **and keep Identity Bridging OFF**. Bridging asserts an identity for
  every routed request; an unauthenticated callback has none, so it fails before
  reaching the app — and whitelisting alone does **not** waive bridging. See
  [Troubleshooting](troubleshooting.md).

### Verify

```bash
# Unsigned request must be rejected
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" --data "payload=%7B%7D" \
  https://<your-host>/api/slack/interactions        # expect 401
```

`401` proves the endpoint is reachable *and* enforcing signatures. A `404`,
redirect, or timeout means the proxy is not passing the path. Test from **outside**
your network — split DNS may resolve the host internally and bypass the gateway
entirely, which makes a broken external path look healthy.

---

## Using it

A new request posts a message with an **Access duration** menu (Permanent, 5
minutes, 15 minutes, 1 hour, 8 hours, 24 hours, 7 days, 30 days) and
**✓ Approve** / **✗ Reject**.

- Pick a duration **before** clicking Approve; leaving it on *Permanent* grants
  standing access. Any other value creates a time-bound (JIT) grant that is
  automatically revoked at expiry — see the JIT behavior in
  [`docs/design/iga-foundations.md`](design/iga-foundations.md) §1.2.
- Slack-initiated timed grants use the default re-request policy
  (re-requestable after expiry).
- The message is replaced with the outcome and the deciding identity.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Buttons do nothing | Endpoint not reachable from the internet (proxy path/UAG whitelist), or Interactivity Request URL not set |
| *"You are not an authorized approver"* | Clicking user's id is missing from `SLACK_APPROVER_MAP` |
| Every click fails, logs show *invalid/absent signature* | `SLACK_SIGNING_SECRET` empty, wrong, or the container wasn't recreated after the env change |
| Message posts without buttons | `SLACK_ACTIONABLE` not `true`, or `WEBHOOK_FORMAT` is not `slack` |
| Nothing posts at all | `WEBHOOK_URL` blank or revoked in Slack |
| Buttons appear but clicks 401 in logs | Message was posted by a *different* webhook than the app that owns the Interactivity URL — Slack routes clicks to the posting app |
