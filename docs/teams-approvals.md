# Actionable Teams Approvals

Post each new access request to a Microsoft Teams channel as an **Adaptive Card**
with **Approve** / **Reject** buttons. The buttons open the request in the
Approval Tool with that decision pre-selected, so an approver goes from the card
to a completed decision in two clicks.

> Optional, **disabled by default**. Without `TEAMS_ACTIONABLE=true` the tool
> sends the plain-text Teams notification.

---

## Why the buttons open the tool instead of deciding in Teams

Slack approvals happen entirely in chat. Teams cannot do the same cheaply, for
two concrete reasons:

1. **Office 365 connectors are retired.** Microsoft began retiring them in
   August 2024 and completed the rollout in 2026. Connectors were what made
   `Action.Http` cards work — a card can no longer POST directly to your service.
   The replacement is a **Power Automate workflow**.
2. **A Power Automate callback needs a premium licence.** A flow *can* post a
   card, wait for the click, and call your API — but calling an external API uses
   the **HTTP action**, a premium connector that consumes credits per run.

So the buttons are `Action.OpenUrl` deep links. That choice has real advantages
beyond cost:

- **No new inbound endpoint, no shared secret, no HMAC.** Nothing to expose or
  rotate.
- **Stronger identity.** The approver authenticates with the tool's own OIDC
  login, so the decision is attributed to a verified user — not to "whoever can
  see the message" (the Slack model has to compensate for this with an explicit
  approver map).
- **The Power Automate flow stays trivial** — one template, no custom logic.

The trade: the decision happens in a browser tab rather than inside Teams.

---

## 1. Create the workflow

In Teams, open the **Workflows** app → **Templates** → search `webhook` → choose
**"Send webhook alerts to a channel"** (this is the current name for the
*When a Teams webhook request is received* trigger).

1. Pick the **team and channel** that should receive approvals.
2. Finish the template, then click **Copy webhook link**. The URL is a Power
   Automate address on `*.environment.api.powerplatform.com` — this is your
   `WEBHOOK_URL`. (Retired Office 365 connectors used `webhook.office.com`; a
   URL on that domain is the old connector and no longer works.)

The tool posts the standard envelope a workflow expects:

```json
{
  "type": "message",
  "attachments": [
    { "contentType": "application/vnd.microsoft.card.adaptive", "content": { … } }
  ]
}
```

If the template's post step doesn't render the card, edit the flow's
*Post card in a chat or channel* action so its message body is the **raw trigger
body** rather than a text field.

## 2. Configure the tool

Set these in the tool's **env file** — `omnissa-approvals.env` for the
ZimaCube/Docker deployment, `.env` for the bundled Compose files (or as container
environment values if your platform manages them that way):

| Variable | Example | Purpose |
|---|---|---|
| `WEBHOOK_URL` | *(the workflow URL)* | Where cards are posted |
| `WEBHOOK_FORMAT` | `teams` | Selects Teams formatting |
| `TEAMS_ACTIONABLE` | `true` | Send the Adaptive Card instead of plain text |
| `APP_BASE_URL` | `https://approvals.example.com` | **Required** — the public URL used to build the deep links |

`APP_BASE_URL` is mandatory for actionable cards: notifications are sent from a
background thread with no HTTP request, so the public URL cannot be derived from
forwarded headers. If it is blank the tool falls back to the plain-text
notification rather than emitting broken links.

Env changes need a container **recreate**, not a restart:

```bash
docker compose -f <compose file> up -d --force-recreate
```

## 3. Reachability

Only the **approver's browser** needs to reach the tool — Teams itself never
calls it. If the admin UI is LAN-only, approvers must be on the LAN or VPN to
follow the links. Unlike the Slack integration, **no new internet-facing
endpoint is required**.

---

## Using it

A new request posts a card with the app name, the requester's name, and three
buttons:

| Button | Effect |
|---|---|
| **✓ Approve…** | Opens the request with the review dialog open and **Approve** pre-selected — choose an access duration and submit |
| **✗ Reject…** | Same, with **Reject** pre-selected — choose temporary or permanent |
| **Open request** | Opens the request detail page with no decision pre-selected |

The full set of options — time-bound (JIT) durations, the re-request policy,
permanent vs temporary decline — is available on the resulting screen. See
[Access Lifecycle](access-lifecycle.md).

Decisions made this way are attributed to the signed-in admin, exactly as if the
request had been opened from the queue.

## Who can see, and who can act

These are two different properties, and the tool controls only one of them.

**Acting is governed by roles.** The buttons are deep links, so clicking one
opens the request in the tool and the approver signs in as usual. Every
authorization rule applies exactly as in the web UI — someone with only the
Viewer role who clicks *Approve* authenticates successfully and then finds no
approve control. The link carries the *intent*; it confers no authority, and it
does not submit anything on its own. See [Roles](../README.md#roles).

**Seeing is governed by channel membership, which the tool cannot enforce.** The
card is posted to a Teams channel, so **every member of that channel can read
the request details** — application name, requester and timing — regardless of
their role, or of whether they have an account in the tool at all. Anyone in the
channel can also click a button; they are simply sent to sign in and then
cannot decide.

If the channel is broader than the set of people who should know who is
requesting what, that is an information-disclosure question to settle through
channel membership — roles cannot fix it. Treat the approvals channel as having
the same audience as the request queue itself.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Card posts but shows as raw JSON | The flow's post action is sending the payload as text — set it to the raw trigger body |
| Plain text arrives instead of a card | `TEAMS_ACTIONABLE` is not `true`, `WEBHOOK_FORMAT` is not `teams`, or `APP_BASE_URL` is blank |
| Buttons open a broken/unreachable URL | `APP_BASE_URL` is wrong, or the approver is off-network for a LAN-only deployment |
| Nothing posts at all | The workflow URL is wrong or the flow is turned off — check the run history in Power Automate |
| Older `webhook.office.com` URL stopped working | That is a retired Office 365 connector; recreate it as a workflow (above) |
