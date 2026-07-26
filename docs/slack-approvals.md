# Actionable Slack Approvals

New access requests post a Slack message with **Approve**, **Reject** and
**Open request** buttons. The buttons open the request in the Access Approval
Tool with the decision pre-selected — you sign in as usual and confirm there.

> Not an Omnissa product — see [NOTICE.md](../NOTICE.md). Intended for
> testing/demo use only.

## How it works

1. Omnissa Access posts a callout to this tool; the request lands in the queue.
2. The tool posts a Slack message to the channel behind `WEBHOOK_URL`.
3. An approver clicks a button, which opens
   `https://<your-host>/requests/<id>?action=approve|reject` in the browser.
4. They sign in (if not already), and the review dialog opens with that decision
   pre-selected — choose an access duration, or temporary vs permanent decline,
   and submit.

### Why the buttons open the tool instead of deciding in Slack

Slack **can** deliver a decision straight from the message, and this tool did
that originally. The problem is authorization.

An interaction callback arrives at an endpoint where **no signed-in user
exists**. Slack signs the request, which proves it came from your workspace —
not that the person clicking may approve anything. Authority therefore had to
come from a separate list, `SLACK_APPROVER_MAP`, and that list is a second
source of truth that drifts: removing someone from an approver group in Omnissa
Access revoked their access to the web UI immediately, but left their Slack
buttons working until somebody remembered to edit the env file. It failed
**open**, silently, with no error and no audit entry.

Deep links remove the divergence rather than manage it. The approver
authenticates with the tool's own OIDC login, so their [role](../README.md#roles)
is resolved from Omnissa Access group membership exactly as it is everywhere
else. Someone who no longer holds an approver role can still click the button —
they simply land on a read-only request page.

It also means:

- **no inbound endpoint** — nothing to expose through your reverse proxy,
- **no signing secret and no approver list** to configure or keep in step,
- **decisions attributed to the real signed-in user**, not to a mapped identity,
- **stale messages are harmless** — the request page reads live state, so a
  button clicked after the request was already decided just shows the outcome.

Teams approvals work the same way, for the same reason.

## 1. Create the Slack app

At <https://api.slack.com/apps> → **Create an App** → **From scratch**, name it,
and pick your workspace.

Then **Incoming Webhooks** → toggle **On** → **Add New Webhook to Workspace** →
choose the channel that should receive approvals → **Allow**. Copy the URL:

```
https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX
```

That is the only value you need. There is no signing secret, no bot token, no
app-level token, no interactivity Request URL, and no OAuth scope beyond
`incoming-webhook`.

## 2. Configure the tool

Set these in the tool's **env file** — `omnissa-approvals.env` for the
ZimaCube/Docker deployment, `.env` for the bundled Compose files (or as
container environment values if your platform manages them that way):

| Variable | Example | Purpose |
|---|---|---|
| `WEBHOOK_URL` | `https://hooks.slack.com/services/T…/B…/…` | Where messages are posted |
| `WEBHOOK_FORMAT` | `slack` | Required for Slack formatting |
| `SLACK_ACTIONABLE` | `true` | Adds the decision buttons |
| `APP_BASE_URL` | `https://approvals.example.com` | **Required** — the public URL used to build the deep links |

`APP_BASE_URL` is mandatory for actionable messages: notifications are sent from
a background thread with no HTTP request, so the public URL cannot be derived
from forwarded headers. If it is blank the tool sends the plain-text
notification rather than emitting broken links.

`WEBHOOK_URL` carries **all** tool notifications (new requests, decisions, and
undeliverable notices), so they all land in the chosen channel.

Env changes need a container **recreate**, not a restart:

```bash
docker compose -f <compose file> up -d --force-recreate
```

## 3. Reachability

Only the **approver's browser** needs to reach the tool — Slack itself never
calls it. If the admin UI is LAN-only, approvers must be on the LAN or VPN to
follow the links. **No inbound endpoint is required.**

## Using it

A new request posts a message with the app name, the requester's name, and three
buttons:

| Button | Effect |
|---|---|
| **✓ Approve…** | Opens the request with the review dialog open and **Approve** pre-selected — choose an access duration and submit |
| **✗ Reject…** | Same, with **Reject** pre-selected — choose temporary or permanent |
| **Open request** | Opens the request detail page with no decision pre-selected |

The full set of options — time-bound (JIT) durations, the re-request policy,
permanent vs temporary decline — is on the resulting screen. See
[Access Lifecycle](access-lifecycle.md).

Decisions made this way are attributed to the signed-in admin, exactly as if the
request had been opened from the queue.

## Who can see, and who can act

These are two different properties, and the tool controls only one of them.

**Acting is governed by roles.** The buttons are deep links, so clicking one
opens the request in the tool and the approver signs in as usual. Every
authorization rule applies exactly as in the web UI — someone holding only the
Viewer role who clicks *Approve* authenticates successfully and then finds no
approve control. The link carries the *intent*; it confers no authority, and it
submits nothing on its own. See [Roles](../README.md#roles).

**Seeing is governed by channel membership, which the tool cannot enforce.** The
message is posted to a Slack channel, so **every member of that channel can read
the request details** — application name, requester and timing — regardless of
their role, or of whether they have an account in the tool at all. Anyone in the
channel can also click a button; they are simply sent to sign in and then cannot
decide.

If the channel is broader than the set of people who should know who is
requesting what, that is an information-disclosure question to settle through
channel membership — roles cannot fix it. Treat the approvals channel as having
the same audience as the request queue itself.

## Troubleshooting

| Symptom | Cause |
|---|---|
| No message appears | `WEBHOOK_URL` unset or wrong, or `WEBHOOK_FORMAT` is not `slack`. Check the app log for a `Webhook notification failed` WARN. |
| Message appears but has no buttons | `SLACK_ACTIONABLE` is not `true`, or `APP_BASE_URL` is blank — the tool falls back to plain text rather than emitting dead links. |
| Buttons open a URL that does not resolve | `APP_BASE_URL` does not match how approvers actually reach the tool. It must be the public URL, and it must match the OIDC redirect URI's host or sign-in will fail. |
| Clicking a button shows the request read-only | Working as intended — that account does not hold an approver role. See [Roles](../README.md#roles). |
| A decided request still shows buttons | The Slack message is a snapshot from when the request arrived. Clicking it is harmless: the request page shows the current state. |
