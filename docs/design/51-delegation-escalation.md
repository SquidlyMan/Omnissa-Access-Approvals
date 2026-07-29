# #51 — Delegation and escalation

Second revision. The first was reviewed by five independent reviewers — IGA
practice, security, reliability, operator experience, and an adversarial pass —
and roughly two-thirds of it was cut. What follows is what survived, plus the
things they found that the first version missed.

Supersedes `iga-foundations.md` §1.3 and resolved decision #4, which this
reconciles. Additive nullable columns only; no backfill, no migration under
`ddl-auto=update`.

## The problem

A request nobody attends to has exactly one outcome: the expiry rule
auto-rejects it after N days. There is no middle. Nobody is told the queue is
being ignored, and the requester finds out by being denied.

## What this ships

Two things, deliberately small:

- **Claim / Release** — an approver takes visible ownership. Advisory only.
- **One escalation stage** — after N minutes pending, the tool posts to the chat
  channel already configured. Plus a manual **Escalate now**.

Everything else the first revision proposed is cut. The reasoning for each cut
is recorded below, because a design that only says what it does invites the
same ideas back next quarter.

## Decisions

### D1. Assignment is advisory, never authorization

Resolved decision #3 (role-only scoping) stands: **any APPROVER may act on any
request**, claimed or not.

Making a claim authoritative would mean a claimed request becomes undecidable
the moment its owner is unavailable — a convenience turned into an outage — and
would put authority in a column Access has never heard of, which is the
`SLACK_APPROVER_MAP` failure 1.16.1 deleted.

**This must be asserted at the API level, not in a unit test**: a request
claimed by A, decided by B over HTTP, returns 200. The realistic way this
guarantee dies is not a deliberate decision but a later well-meaning change that
hides the Approve button when `assignedOwner != me`. The test is what stops that.

### D2. Delegation is self-claim, with a TTL

An approver claims a request; `assignedOwner` is set from **their own session
identity**. No user directory is needed, which matters because OIDC users are
never persisted — the only enumerable humans in the system are the local
break-glass accounts, so an "assign to…" picker would offer a list that is both
near-empty and wrong.

**A claim expires.** After `claimTtlMinutes` (default: the escalation interval)
an unactioned claim is released automatically, audited as `request-released`
with actor `system`.

The TTL exists because the reviewers were split on whether claiming helps at
all, and the objection was specific: Alice claims at 17:00 and goes home; Bob
sees an owner badge, reads it as handled, and does nothing. An abandoned claim
is a *worse* signal than no claim. A claim that lapses cannot rot, and unlike
exclusivity it can never deny an approval.

Release is unrestricted — any APPROVER may release any claim, because the
alternative is a request welded to someone who has left. `request-released` must
name the owner being released and how long they held it, or a handoff chain
cannot be reconstructed from the trail.

### D3. Escalation applies to every pending request

Not only claimed ones. The common failure is a request nobody looks at — it has
no owner *because* it was ignored. Restricting escalation to claimed requests
would exempt the case it exists for.

### D4. One stage, to the channel

The chat channel already configured. No new identity surface, no new
credential, no new delivery path. Reuses the existing webhook and the
`Action.OpenUrl` deep links — **never a callback**, per the 1.16.1 reasoning.

**The email stage is cut.** It was two-thirds of the engineering and the sole
reason the scheduler hazard existed: every mitigation in the first revision —
the directory snapshot, the paged SCIM read, the off-thread refresh,
defer-to-next-pass, the stale-snapshot health surface — existed only to make it
safe. Cutting it deletes the hazard rather than mitigating it.

Three findings made the cut easy rather than reluctant:

- It would resolve to **zero recipients** on the installs this tool is built
  for. A lab deployment runs local break-glass accounts holding `ROLE_APPROVER`
  in the H2 authority table. Those accounts are in no Access group and no role
  map. The first revision argued the only enumerable humans are the local
  accounts, and then designed a recipient lookup that ignores them.
- It assumed an OIDC `group_ids` claim value is a key into `GET
  /scim/Groups/{id}`. Unverified — and this project has already been burned by
  exactly that assumption: `iga-foundations.md` §1.2 records that the callout's
  numeric `userId` cannot be mapped to a SCIM id. Access has more than one id
  space.
- Escalation mail on a timer removes the human from the send path. Since
  `POST /api/approvals/new` is `permitAll` and every field of the callout is
  attacker-chosen — including the address `MailNotification` reads — that turns
  an unauthenticated endpoint into outbound mail from the org's relay to an
  address of the sender's choosing. Filed separately as a hardening task.

Deferred to its own task, to be reconsidered only once the callout endpoint is
authenticated and mail has bounded timeouts.

### D5. Manual escalation is a first-class action

**Escalate now** on the request detail page, gated on `canDecide`.

Not a demo hack. PagerDuty and Opsgenie both ship this and document that it
skips the remaining timer; this project already has the precedent in
**Pull from Access**, a manual trigger for a background reconciliation.

It earns its place twice over. Escalation is otherwise unobservable until it
fires, so this is **the only way an admin can confirm the rule is configured
correctly** — and the only way the feature can be shown in a walkthrough rather
than described.

It advances `escalationStage` exactly as the sweep does, so the timed stage
never re-fires. It is audited with the **admin as actor**, not `system`, and the
message says the timer had not elapsed — the trail must never imply a timer
fired when a human pressed a button.

### D6. Escalation is a stage of the expiry rule

One rule reads: *nudge the channel at 4 hours, reject at 3 days.*

The mature tools keep escalation in a named policy object referenced by many
rules — PagerDuty escalation policies, Opsgenie escalations, ServiceNow
`contract_sla`. That rationale is reuse across many services. A tenant here has
one expiry rule. A second table, a second CRUD page and a "which policy?"
dropdown would be more configuration, not less.

So: on the rule, but **visually separated in the form** — a collapsed
`Escalation (optional)` section. That buys the conceptual separation the mature
tools are actually after, at zero schema and zero documentation cost.

### D7. Scoping is honest about being global

The first revision claimed escalation would apply to requests matching the
rule's `appPattern`/`groupName`. That promise was unreachable: `RulesPage.tsx`
hard-codes both to `null` for every expiry rule the form creates, and
`RuleEngine.matches()` returns **false** when a rule has neither criterion — so
applying it literally would have made escalation, and every existing expiry
rule, match nothing.

**The expiry-matcher bug is real and is being fixed — separately, before this
feature.** It ships as its own change with its own changelog entry, with
"no criteria" defined as *matches everything* (not `RuleEngine`'s matches
nothing), and with the missing App-pattern and Group fields added to the expiry
form so the fix is reachable at all.

Until that lands, **escalation is global to all pending requests** and this
document says so rather than implying scoping it does not have.

## Data model

### `CalloutRequest` — four nullable columns

| Column | Type | Meaning |
|---|---|---|
| `assignedOwner` | `String` | app-identity string of the claiming approver; `null` = unclaimed |
| `assignedAt` | `Date` | when claimed — also the TTL clock |
| `escalatedAt` | `Date` | when escalation fired; `null` = not yet |
| `escalationStage` | `Integer` | `null`/`0` none, `1` fired. A counter rather than a boolean so a second stage needs no schema change |

Names reconciled here: §1.3 said `escalatedTo`/`escalationTimeoutMinutes`,
decision #4 said `escalationTarget`/`escalationTimeout`. **These names win.**
Timeouts live on the rule, so no timeout column exists.

**`escalatedTo` is cut.** With one stage it would always hold the literal string
`"channel"`, and a free-text *"description of who was reached"* is a second home
for what `AuditEvent` already records — and an invitation for a later change to
put addresses into the audit trail, the log file, and the **unredacted log
bundle**. The outcome goes in the audit message.

**`escalationStage` is scoped to one rule.** A single counter cannot represent
two rules with different stages — if rule A fires stage 1, rule B's stage 1
silently no-ops. With one stage and one expiry rule this cannot arise; it is
recorded here as a constraint to revisit if a second stage ever lands.

### `AutoRule` — two nullable columns

| Column | Type | Meaning |
|---|---|---|
| `escalateAfterMinutes` | `Integer` | channel nudge after N minutes pending; `null` = off |
| `claimTtlMinutes` | `Integer` | auto-release an unactioned claim; `null` = inherit `escalateAfterMinutes` |

**`kind` is cut.** All five reviewers rejected it. It would be added NULL by
`ddl-auto=update`, and the first `AND kind = 'expiry'` query would silently stop
every pre-existing rule from firing, because `NULL = 'expiry'` is never true.
`RulesController` binds `@RequestBody AutoRule` directly with no validation, so
a client could persist `kind:"match"` on a row with `expiryDays` set and the two
would disagree permanently with nothing to detect it. It is derived from
`expiryDays != null`, which cannot drift. Derive it in the response DTO.

**`discloseApprovers` and `OMNISSA_ESCALATION_DISCLOSE_APPROVERS` are cut** with
the email stage. **No new environment variables** — which removes the
eight-file documentation cascade a single new config key triggers in this repo.

**Validation** in `RulesController`, alongside the existing checks:

- `escalateAfterMinutes < expiryDays × 1440`. A stage scheduled after the
  request would already be rejected can never fire, and would fail silently.
- Stages require `action = "reject"` and `expiryDays`, matching how expiry rules
  are already constrained.

## The sweep

A fourth `@Scheduled` method in `RuleScheduler`, `fixedDelay=PT5M`, registered
with `SchedulerHeartbeat` under `ESCALATION = "escalation"`.

**It makes no network calls of its own** beyond the existing fire-and-forget
webhook. The single-threaded hazard is therefore absent rather than mitigated —
`SchedulerHeartbeat`'s javadoc warns that one wedged job stops the rest, and if
JIT expiry stops, time-bound access silently never expires while every health
check stays green.

Per enabled expiry rule with `escalateAfterMinutes` set:

```
for each pending request older than escalateAfterMinutes:
    re-fetch by requestId          # entity may be detached
    if state != "pending":  skip   # a human may have decided it mid-pass
    if escalationStage >= 1: skip
    notify, then save stage, then audit
```

Five details that are not incidental:

- **Re-check `state` after the re-fetch.** The sweep reads a list, then loops
  with I/O per firing. A human can approve a request mid-pass, and the existing
  JIT sweeps do not re-check. Without this, the channel is told "nobody has
  acted on this" about a request approved ninety seconds ago.
- **Notify, *then* save.** A crash between the two re-sends a nudge; the reverse
  order records a stage that never fired and, because each stage fires once by
  design, never fires again. A duplicate nudge is bounded noise; a missed
  summons is the failure the feature exists to prevent. Chosen deliberately.
- **Per-pass cap of 50**, remainder deferred. Enabling a rule on an existing
  backlog would otherwise escalate hundreds of requests in one pass. Hitting the
  cap is logged.
- **First-enable guard.** When a rule's `escalateAfterMinutes` goes from null to
  set, skip requests older than `2 ×` the interval on the first pass and log how
  many were skipped. Retro-escalating the entire history is not a feature.
- **Wrap each request individually** in `try/catch`, as the JIT sweeps do
  (`RuleScheduler.java:182`). `requestId` has no unique constraint, so a
  duplicate makes `findByRequestId` throw — one poisoned row must not abort
  every pass.

**Claim TTL** is handled in the same sweep: a claim older than
`claimTtlMinutes` with the request still pending is cleared and audited.

### `receivedDate` is the wrong clock, knowingly

Age is measured from `receivedDate`, which is a field initializer — nothing ever
calls `setReceivedDate`. A request recovered by **Pull from Access** therefore
gets a `receivedDate` of the pull, not of when Access began holding it. Those
are precisely the requests ignored longest, and escalation will under-report
their staleness.

Not fixed here, because the fix is a new `sourceRequestedAt` column populated
from Access and a `coalesce()` across both paths — a change to expiry semantics
that deserves its own discussion. Recorded so the behaviour is known rather than
discovered.

## Notifications

`WebhookNotifier` gains `notifyEscalated(request)` + `buildEscalatedPayload`,
following the five existing pairs.

- **Visually distinct.** Prefixed `⏰ Still waiting (4h) —`, and for Teams the
  Adaptive Card container style set to `attention`. Escalations rendered
  identically to new requests would read as five *new* requests, which is worse
  than noise.
- **Gated under the existing `webhook.notify-lifecycle`**, not a new flag.
  Escalation is a lifecycle event, and that flag exists precisely to let
  operators mute the chattier ones.
- **Same deep-link buttons.** The action needed is identical; only the urgency
  differs.

**It must report what actually happened.** All five existing `notify*` methods
return early and silently when `webhook.url` is blank — a supported
configuration. Inheriting that would mark every request escalated while nothing
was sent, and the once-only rule would prevent any retry. So `notifyEscalated`
returns an outcome (`SENT` / `NOT_CONFIGURED` / `FAILED`), mirroring
`DecisionOutcome` and `RevokeOutcome`; the stage advances on `SENT` and
`NOT_CONFIGURED`, and `FAILED` is left for the next pass exactly as the JIT
sweeps leave `UNREACHABLE`.

## API and UI

- `POST /api/approvals/requests/{requestId}/claim`, `/release`, `/escalate` —
  **all three must be added to the explicit POST list in `SecurityConfig`**
  (~line 255). The fallback at `/api/approvals/**` grants
  `ADMIN, APPROVER, VIEWER, USER`, so forgetting produces no error and lets a
  **Viewer claim requests**. This is the same hand-enumerated-list failure
  1.19.2 removed from routing; here the miss fails open.
- Queue row: an owner badge and, when escalated, an amber `⏰ Escalated` chip.
  **The badge renders a display name or local-part, never the raw identity** —
  `AuditService.currentAdmin()` falls back to `email` on tenants that emit no
  `preferred_username`, which is this one, so a naive badge publishes an
  approver's email address to every Viewer.
- Request detail: an `Escalation` row reading either `Nudged the chat channel —
  2 Jul 14:03` or, before it fires, `Chat nudge in 2h 14m`. The pending form is
  what answers *"is this configured correctly?"*.
- Rules list: `describeRule()` extended to state the whole policy — *"Nudge chat
  after 4 hours, then auto-reject after 3 days."* The rule row is the only place
  the policy is visible.
- Rules form: one **duration widget** (number + minutes/hours/days), used for
  `escalateAfterMinutes` *and* the existing `expiryDays`, all persisted as
  minutes, with a live sentence and an inline ordering error. Mixing a minutes
  field with a days field and asking an admin to verify `2880 < 4320` mentally
  is a trap; the current failure mode is a server 400.

**No "Mine" tab.** As specified it was broken three ways — `state=mine` reaches
`findByStateOrderByIdDesc` and returns an empty page, client-side filtering only
sees the current 20-row page, and `visibleTabs` would show it to VIEWERs. It is
also the highest-leverage path to eroding D1. The owner badge does the job; if
filtering is wanted later, a *"show only mine"* checkbox on the existing server
query.

## Audit

Three new actions: `request-claimed`, `request-released`, `request-escalated` —
each added to the `AuditAction` union in `types.ts` and `AUDIT_ACTION_STYLES` in
`QueuePage.tsx`, or they render grey.

- `request-released` names the owner released and how long they held it, and
  distinguishes a manual release from a TTL lapse by actor.
- `request-escalated` names the **rule id**, matching the existing expiry audit
  (`RuleScheduler.java:95-98`). Without it the trail cannot show which policy
  fired.
- Manual escalation records the **admin** as actor and says the timer had not
  elapsed.

## Observability

`SchedulerHeartbeat` gains `ESCALATION` with a **20-minute** tolerance — reusing
`HOURLY_STALE_AFTER` would hide a 35-cycle stall of a 5-minute job. Note
`anyStale()` and `detail()` are hardcoded three-way lists; adding the constant
without editing both leaves the new job unmonitored.

One gauge is worth more than the rest: **`escalationsOverdue`** — pending
requests past the threshold whose `escalationStage` has not caught up. In steady
state it is 0. A persistently nonzero value is the single signal that says
*"escalation is running and accomplishing nothing"*, covering webhook
misconfiguration, matcher bugs and sweep stalls in one number. Reported in the
session-gated `detail()`, **not** in the public aggregate — flipping the
unauthenticated `{"status":"UP"}` for a benign condition trains operators to
ignore the one alarm that catches a wedged scheduler.

## Tests

- **D1 at the API level**: claimed by A, decided by B over HTTP, 200.
- Stage fires once; a restart does not re-fire; a mid-pass decision is skipped.
- `NOT_CONFIGURED` when `webhook.url` is blank — asserting the audit does *not*
  claim the channel was notified.
- `FAILED` leaves the stage unadvanced and retries next pass.
- Claim TTL releases an unactioned claim and audits it as `system`.
- Manual escalation advances the stage so the timed sweep does not re-fire, and
  records the admin as actor.
- Validation rejects `escalateAfterMinutes >= expiryDays × 1440`.
- Per-pass cap and first-enable guard, both asserting the skipped count is
  logged.
- A duplicate `requestId` fails one request, not the sweep.

## Cut, and why — for the next person

| Cut | Reason |
|---|---|
| Stage 2 email | Sole cause of the scheduler hazard; zero recipients on local-account installs; unverified SCIM id assumption; unauthenticated amplification path |
| `DirectorySnapshot`, async refresh, defer-to-next-pass, stale-snapshot health | Existed only to make stage 2 safe |
| `discloseApprovers` + its env var | Went with stage 2; a recipient count delivers the whole stated benefit |
| `AutoRule.kind` | Derived and persisted; NULL under `ddl-auto=update`; unvalidated on write |
| `escalatedTo` | Always `"channel"`; duplicates the audit trail; invites addresses into the log bundle |
| "Mine" tab | Broken three ways as specified; erodes D1; worth nothing at two approvers |
| Per-rule escalation scoping | Unreachable until the expiry matcher is fixed and the form exposes the fields |

**Schema: `CalloutRequest` +4, `AutoRule` +2** — down from +5 and +4. `AutoRule`
stays at eight fields rather than ten. **No new environment variables.**
