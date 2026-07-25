# Access Lifecycle — Grant, Expire, Revoke, Re-request

Beyond a simple yes/no, the tool manages the **whole life of an access grant**:
time-bound (JIT) approvals that expire on their own, declines that can block a
user from asking again, on-demand revocation of an app already in use, and a
recovery path to undo any block.

Every action here manipulates entitlements in Omnissa Access through the OAuth
service client, is written to the audit trail, and (optionally) announced to your
webhook/Slack channel.

---

## The mechanism: per-user exclusions

Access grants an app to **users** or to **groups**. Removing a user's entitlement
only works when the grant is user-level; a group-provisioned user has no per-user
entitlement to delete. The tool therefore works with a per-user **exclusion** — a
`negative` entitlement that overrides group access for one person **without
touching the group**:

| Operation | Effect in Access |
|---|---|
| **Exclude** (revoke) | Directly-assigned user → flipped to *Exclude*. Group-assigned user → an *Exclude* entry is added. Either way Access **deprovisions the running app** (it emits a deactivation callout within seconds). |
| **Un-exclude** (restore) | Group-assigned → the exclusion entry is deleted and the group entitlement takes over again. Directly-assigned → the user is re-provisioned with their original **Deployment Type**. |

The user's Deployment Type (*User-Activated* or *Automatic*) is captured when
access is granted and written back on restore, so restoring never silently
converts an Automatic assignment.

> **Prerequisite:** the service client needs rights to read and modify catalog
> entitlements in your tenant. See
> [Omnissa Access setup](omnissa-access-setup.md).

---

## Approving

When approving a request you choose an **Access duration**:

| Choice | Behavior |
|---|---|
| **Permanent** (default) | Standing access. Nothing expires. |
| **5 / 15 minutes, 1 / 8 / 24 hours, 7 / 30 days** | Time-bound (JIT). Access is automatically revoked when the time runs out. |

For a timed grant you also choose what happens **after expiry**:

- **Allow the user to re-request** *(default)* — at expiry the user is excluded
  (the app is removed), and after a short hold the exclusion is lifted so the app
  returns to a **requestable** state.
- **Off — one-time** — at expiry the user is excluded and **stays** excluded; the
  app does not reappear.

Auto-approval rules can grant timed access too: set **Grant duration** on an
approve rule and every auto-approved request becomes a JIT grant (always
re-requestable).

### Timing

The expiry sweep runs **every minute**, so a 5-minute grant is revoked 5–6
minutes after approval. The re-open happens about **one minute** after the
revoke — the delay is deliberate, giving Access time to finish deprovisioning
before the app is made available again.

---

## Rejecting

Rejection has two modes:

| Mode | Behavior |
|---|---|
| **Temporary** (default) | Only this request is rejected. The app stays available and the user may request it again. |
| **Permanent** | The user is **excluded** from the app in Access. It will not reappear for them until an admin lifts the block. |

If a permanent decline **cannot be applied** (Access unreachable, or the user's
identity can't be resolved), the tool does **not** record it as permanent: the
request stays re-requestable, the message says the exclusion failed, and an
`access-block-failed` entry is written. A block is never claimed unless it
actually took effect.

---

## Revoking access that is already granted

Open an **approved** request to revoke it on demand — no need to wait for a TTL.

| Action | Behavior |
|---|---|
| **Revoke access** | The user is excluded (the app is removed from them), then after about a minute the exclusion is lifted and the app returns to a **requestable** state. |
| **Revoke and block** | The user is excluded and **stays** excluded. The app will not reappear until an admin lifts the block. |

Both are confirmation-gated. As with a permanent decline, a revocation that
cannot be applied changes nothing — the request stays `approved` and no
notification is sent.

> ### Note — access can be re-granted automatically
> After a **Revoke access** (or a re-requestable JIT expiry), two things can hand
> the app straight back:
> - **Deployment Type = Automatic** on the group assignment — Omnissa Access
>   re-provisions the app the moment the exclusion is lifted; the user never has
>   to request it.
> - **A matching Auto-Approval Rule** in this tool — the user's new request is
>   approved immediately.
>
> Use **Revoke and block** if the access must stay gone.

---

## Allowing a re-request (undoing a block)

Any request left in a blocked state — from a **permanent decline**, a
**Revoke and block**, or a **one-time** JIT grant — shows an **Allow re-request**
action on its detail page. Blocked records live in the **Deactivated** section of
the queue.

Lifting the block removes the exclusion in Access (restoring the group
entitlement, or re-provisioning a directly-assigned user with their original
Deployment Type) so the user can request the app again. The action is audited as
`access-reopened`.

---

## Deciding from Slack

With [actionable Slack approvals](slack-approvals.md) enabled, the same choices
are available in chat:

| Button | Equivalent to |
|---|---|
| **✓ Approve** + duration menu | Approve, with the chosen Access duration (re-requestable) |
| **✗ Reject** | Temporary decline |
| **⛔ Reject and Block** | Permanent decline (confirmation-gated) |

Revoking an active grant and lifting a block are **web-UI only** — they act on a
request that is no longer in the chat conversation.

---

## Request states

| State | Meaning |
|---|---|
| `pending` | Awaiting a decision |
| `approved` | Approved and delivered to Access |
| `rejected` | Rejected and delivered to Access |
| `deactivated` | Access sent a deactivation callout (the **user** removed the app) |
| `expired` | A decision could not be delivered — the request no longer existed in Access |
| `revoked` | Access was granted, then withdrawn on purpose (TTL expiry or an admin revoke) |

`deactivated`, `expired` and `revoked` all appear under the **Deactivated** tab.
Note the distinction: `expired` is a *delivery failure*, `revoked` is an
*intentional teardown*, and `deactivated` is *user-initiated*.

## Audit actions

| Action | When |
|---|---|
| `request-received` | A callout arrived (or was pulled from Access) |
| `deactivation-received` | Access reported the user removed the app |
| `approved` / `rejected` | An admin (or Slack approver) decided |
| `auto-approved` / `auto-rejected` | An auto-approval rule decided |
| `decision-undeliverable` | Access rejected the decision — the request was gone |
| `access-revoked` | A grant was withdrawn: TTL expiry or an admin revoke |
| `access-reopened` | An exclusion was lifted — the app is requestable again |
| `access-blocked` | A permanent decline excluded the user |
| `access-block-failed` | A permanent decline could **not** be enforced |
| `request-deleted` | An admin deleted the local record (does not touch Access) |
| `slack-rejected` | An unmapped Slack user attempted a decision |

Every entry records the acting identity — including Slack approvers, which are
attributed to the mapped approver rather than `system`.

## Notifications

Decisions and lifecycle events are posted to the configured webhook
(`WEBHOOK_URL`). `access.revoked` and `access.reopened` can be turned off with
`WEBHOOK_NOTIFY_LIFECYCLE=false` — see the
[configuration reference](configuration.md#webhook-notifications).

## API

All are authenticated and CSRF-protected.

| Endpoint | Purpose |
|---|---|
| `POST /api/approvals/response` | Decide a request (`approved`, optional `ttlMinutes`, `reRequestable`) |
| `POST /api/approvals/requests/{id}/revoke?permanent=false\|true` | Revoke an approved grant |
| `POST /api/approvals/requests/{id}/allow-rerequest` | Lift a block |
| `DELETE /api/approvals/requests/{id}` | Delete the local record only |

## Troubleshooting

| Symptom | Cause |
|---|---|
| Grant never expires | The request was approved as *Permanent* — check **Access duration** on the request detail page |
| App comes back immediately after a revoke | Group Deployment Type is *Automatic*, or an auto-approval rule matched — use **Revoke and block** |
| "the exclusion could NOT be applied" | Access was unreachable or the requester's SCIM id could not be resolved; nothing was changed — retry |
| Revoke did nothing and the audit says the user was not entitled | The user's access came from somewhere the tool cannot see (e.g. another group with the app assigned directly) |
| Blocked user still can't request after lifting | Check the app's **Assign** list in Access for a leftover *Exclude* entry |
