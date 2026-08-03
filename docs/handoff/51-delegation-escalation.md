# Handoff brief — #51 Delegation & Escalation

> ## ⚠️ STATUS: BUILT AND SHIPPED — this is now a historical record
>
> **This document was written as a design brief, before the feature existed.
> It shipped in v1.21.0 (2026-08-03).** The reasoning is kept because it
> still explains *why* the feature has the shape it does, but several of its
> stated blockers were resolved before the build, and the scope grew:
>
> - **D7's scoping blocker is gone.** The expiry matcher was fixed (#69), so
>   escalation honours the rule's own `appPattern`/`groupName` rather than
>   being global as this document says.
> - **Escalation notifies the approvers themselves**, not only the chat
>   channel. All three reasons D4 cut that were resolved: the SCIM group-id
>   assumption was verified true against the live tenant, the callout
>   endpoint was hardened (#70), and recipients now resolve from
>   `OMNISSA_ROLE_MAP` rather than from local accounts.
> - **D2's self-claim-only rule was relaxed**: assigning to a named approver
>   exists. It carries no obligation — escalation still fires and the claim
>   TTL still auto-releases, so an unactioned assignment decays exactly like
>   an abandoned self-claim.
> - **Escalation runs on its own thread pool**, which this document does not
>   contemplate. It is the first scheduled job needing answer-bearing network
>   calls *and* a synchronous result; on the shared thread a slow tenant
>   would stall JIT expiry, which fails silently.
>
> **What did NOT change: D1.** Ownership is advisory and never
> authorization, asserted over HTTP exactly as this document demands.
>
> **For how the feature actually behaves, read these instead:**
> - `docs/delegation-escalation.md` — the user-facing guide
> - `docs/publish/documentation.md` §2.8 — the published reference
> - The in-app Help page, section "Ownership and Escalation"
> - `DelegationService` / `EscalationService` / `EscalationSchedulerConfig`
>   javadoc, which carries the per-decision reasoning

**Original purpose**: a self-contained brief for another LLM (no access to
the codebase assumed), giving full project context, the state of the feature
at the time, what had already been decided and why, and the open question we
wanted help reasoning through. A sibling document,
`53-multi-stage-approval.md`, covers a related but separate feature — it has
also since shipped.

Treat everything under "What's already decided" as load-bearing history, not
a straw man to knock down. Two prior design passes already argued through the
obvious first ideas (email escalation, a delegate-picker, a channel-callback
button) and cut them for specific, stated reasons. New proposals are welcome,
but they need to engage with *why* the obvious version was cut, not
re-propose it unmodified.

---

## 1. What this tool is

**Access Approval Tool for Omnissa** — a self-hosted approval gateway that
sits in front of Omnissa Access (formerly VMware Workspace ONE Access), an
identity/SSO/app-catalog product. One Spring Boot 4 (Java 17) backend + one
React SPA, shipped as a single Docker container with an embedded H2 file
database. It is an unsupported personal lab project (MIT license,
non-production), deployed on a home NAS, one Access tenant per deployment.
Public repo: `github.com/SquidlyMan/Omnissa-Access-Approvals`.

**The problem it solves**: Omnissa Access can require admin approval before a
user is granted an application. Access's own built-in approval workflow is
thin, so this tool receives Access's approval webhook ("callout"), shows the
request in a queue, lets a human approve or reject it, and posts the decision
back to Access through its API. Optional extras layered on since: auto-rules
(pattern-match auto-approve/reject/expire), Slack/Teams notifications with
inline approve/reject, JIT/time-bound access grants with automatic
revocation, an audit trail, syslog export.

**Core request lifecycle**:

```
Access → POST /api/approvals/new (callout, unauthenticated endpoint,
          rate-limited + optional HTTP Basic)
       → CalloutRequest row created, state = "pending"
       → shown in the admin queue (live via SSE)
       → an approver (or an auto-rule) decides: approved / rejected
       → decision POSTed back to Access via the service-client API
       → (optional) JIT: access auto-expires after a TTL, tool "excludes"
          the user in Access to revoke it
```

States a `CalloutRequest` can be in today (plain `String` column, not an
enum): `pending`, `approved`, `rejected`, `deactivated`, `expired`,
`revoked`.

**Version at the time of writing**: 1.19.12, with #51 unbuilt. **It shipped
in 1.21.0**; the current version is 1.21.0. What follows is design-doc
history and the re-scoping question that was open at the time — see the
status banner at the top for how it was resolved.

## 2. Identity and authorization model — where it stands today

This is the part most relevant to your question, so it's worth being precise
about what's actually implemented vs. what's aspirational.

**Already shipped (#52, RBAC via Access groups)**:

- Admins can run with **zero local user accounts**. OIDC login ("Sign in
  with Omnissa Access") is fully supported, and local username/password
  login can be disabled entirely (`OMNISSA_AUTH_LOCAL_LOGIN_DISABLED`).
- Roles are `ROLE_ADMIN`, `ROLE_APPROVER`, `ROLE_VIEWER`, `ROLE_AUDITOR` (plus
  a legacy `ROLE_USER` alias for `ROLE_VIEWER`). Roles are **additive** — a
  user in two mapped groups gets the union of both roles' capabilities.
  `ROLE_AUDITOR` is the one *restrictive* role (read the audit trail, not
  the live queue) and is specifically documented as losing its restriction if
  combined with any other role, because additive union means the extra role
  wins. The mapper (`GroupRoleMapper`) logs this conflict rather than
  silently resolving it.
- Mapping is configured as `OMNISSA_ROLE_MAP = <groupId>:<ROLE>,<groupId>:<ROLE>,...`
  — **group IDs, not names** (a rename in Access must not silently drop
  everyone to the default role). Operators read the IDs off an in-app
  endpoint (`GET /api/auth/claims`) which pairs each id with its display
  name.
- The IDs are matched against the OIDC `group_ids` claim, sourced from the
  ID token or, when a user is in more than roughly 20 groups, from an
  automatic userinfo-endpoint round-trip (Access moves large claims behind
  an `ovc`/`ovl` overflow indirection; Spring's default `OidcUserService`
  fetches and merges it transparently — the app never had to write overflow
  handling itself, but also never explicitly resolves `ovc`/`ovl`, so it's
  worth confirming Spring's merge behavior hasn't changed if you touch this
  path).
- Anyone who completes OIDC sign-in but matches **no** configured group gets
  `ROLE_VIEWER` by default (read access to the full queue and audit trail).
  This is a deliberate fallback, not a bug, but it means the tool's
  effective access control is only as tight as (a) which users can complete
  OIDC login against this Access tenant at all, and (b) the group mapping
  being complete. Worth flagging to an operator moving off local accounts
  entirely: if the OIDC client isn't scoped to a restricted Access access
  policy, "no local users" can silently mean "every directory user who can
  reach this app's OIDC client gets Viewer."

**Local accounts, if kept at all**: exist only as a break-glass mechanism.
`LocalAccountService.guardLastAdmin()` refuses to disable/delete the last
*local* admin account — deliberately blind to OIDC admins, because the whole
point is an account that survives an Access/OIDC outage. If an operator goes
fully Access-only, that guard becomes moot (there's no local admin left to
protect), which is a legitimate deployment choice this tool already
supports, but it does mean losing Access reachability = losing the ability
to sign in at all, with no fallback. Not this feature's problem to solve,
but relevant context for anyone reasoning about "no local users."

**NOT yet used anywhere in the app**: SCIM. The only place any Access
identity API beyond OIDC claims and the callout envelope is touched today is
JIT (#49), which resolves a requester's SCIM id **once, at grant time**, via
entitlement-listing lookups (with a documented gotcha: the callout's numeric
`userId` cannot be mapped to a SCIM id directly — `/scim/Users/{n}`,
`externalId eq n`, and the legacy users endpoint all 404 on it). There is no
code today that:
- Calls `GET /scim/Groups/{id}` or any group-membership-listing endpoint.
- Resolves a *group* to its member list or their contact attributes
  (email/phone/UPN/etc.).
- Caches the OAuth token used for Access API calls — `OmnissaRestClient`
  fetches a brand-new token on **every single call**, no caching, no
  connection pooling. This is a real prerequisite to fix before adding any
  feature that increases Access API call volume (e.g., resolving group
  membership on every escalation).

**The open question this creates for #51**: is a group ID in
`OMNISSA_ROLE_MAP` (and the `group_ids` OIDC claim) the *same id space* as a
SCIM `Groups/{id}`? This has never been tested. It matters a lot, because if
true, it means the tool could resolve "who is in the Approvers group,
including their email" at escalation time using config that already exists
— no new admin setup. If false, escalation has no path to real recipients
without inventing a second identity surface. **This project has already
been burned once by an unverified id-space assumption** (the JIT `userId`
→ SCIM id case above) — treat this as a "verify before designing around it"
item, not a given.

## 3. The problem #51 exists to solve

A request nobody attends to has exactly one outcome today: an optional
expiry auto-rule rejects it after N days. There is no middle state. Nobody
is notified that the queue is being ignored; the *requester* finds out by
being denied. #51 is about closing that gap: some combination of (a)
visible ownership/claiming, (b) a way to hand a request to someone else, and
(c) escalation when nothing happens in time.

## 4. What's already decided (design revision 2, unbuilt)

The full doc is `docs/design/51-delegation-escalation.md` in the repo (not
attached here — this section summarizes it). It went through five
independent reviewers (IGA practice, security, reliability, operator
experience, adversarial) and roughly two-thirds of the first draft was cut.
**Decisions, with the reasoning that produced them:**

- **D1 — Assignment is advisory, never authorization.** Any `APPROVER` may
  act on any request, claimed or not, always. This is a *resolved*
  project-level decision, not just this feature's default: making a claim
  authoritative would mean a claimed request becomes undecidable the moment
  its owner is unavailable (a convenience turned into an outage), and would
  put decision authority in a column Access has never heard of — which is
  exactly the shape of a previously-removed feature (see §5, "no second
  source of truth"). Must be tested at the HTTP level (claimed by A, decided
  by B, 200 OK), not just unit-tested, because the realistic way this
  guarantee dies is a later well-meaning UI change that hides the Approve
  button when `assignedOwner != me`.
- **D2 — Delegation is self-claim with a TTL, not an assignment picker.** An
  approver claims a request; the owner field is set from their **own**
  session identity. No "assign to a specific person" UI, because — at the
  time this was written — OIDC users were never persisted anywhere the app
  could enumerate them, so a picker would offer either nothing or the wrong
  list. **This constraint may now be reconsidered** given SCIM group-member
  resolution is on the table (see §2's open question) — that's part of what
  we want help thinking through. The TTL exists because reviewers were split
  on whether claiming helps at all: an approver claims a request and goes
  home, another approver sees the owner badge, reads it as "handled," and
  does nothing — an abandoned claim is a worse signal than no claim. A claim
  therefore auto-releases after `claimTtlMinutes`. Release is unrestricted
  (any approver can release any claim) so a request never gets welded to
  someone who's left.
- **D3 — Escalation applies to every pending request, not just claimed
  ones.** The common failure mode is precisely a request with no owner
  *because* nobody looked at it.
- **D4 — One escalation stage exists today: a chat-channel nudge** (reuses
  the webhook already configured for new-request notifications) **plus a
  manual "Escalate now" button**, gated on the same permission as deciding.
  The manual trigger isn't a demo hack — it's the only way an admin can
  verify escalation is configured correctly before waiting for a timer, and
  precedent for "manual trigger alongside an automatic sweep" already exists
  elsewhere in this tool.
  - **A second stage (email to specific approvers) was designed and then
    fully cut.** This is the part most relevant to your question, so the
    reasoning is worth stating in full — three findings:
    1. It would have resolved to **zero recipients** on the installs this
       tool is actually built for: a lab deployment running local
       break-glass accounts, which are in no Access group and no role map.
    2. It assumed an OIDC `group_ids` value is directly usable as a SCIM
       group lookup key — unverified, and (as above) this project has
       already been burned by a structurally identical assumption
       elsewhere.
    3. The callout-ingest endpoint (`POST /api/approvals/new`) is
       intentionally unauthenticated-by-default (Access doesn't reliably
       pre-send credentials) and every field of the callout is
       attacker-controlled. Timed escalation email removes a human from the
       send path entirely, which — combined with an attacker-chosen
       resource name/notes field reaching an email body — would turn an
       unauthenticated endpoint into a vector for outbound mail from the
       org's relay. (This has since been separately hardened per #70, but
       the "don't let unauthenticated input drive an unattended send" logic
       still stands as a design principle.)
    - **All the machinery that existed only to make the email stage safe**
      (a directory snapshot cache, an async off-thread refresh, a
      defer-to-next-pass rule, a stale-snapshot health surface) was cut
      along with it, on the theory that removing the hazard beats mitigating
      it. If a revived design reintroduces recipient resolution, expect to
      need an answer for *some* version of "what happens when the directory
      lookup is slow or fails," even if it's simpler than what was cut.
  - There is an **open backlog item, #71**, literally titled "Reconsider
    escalation stage 2 (email approvers) once its prerequisites land" — this
    handoff is effectively the research for that item.
- **D5 — Manual escalation ("Escalate now") is first-class**, not a demo
  hack, gated the same as deciding. It advances the same stage counter the
  timed sweep uses, so the timed stage doesn't double-fire, and it's audited
  with the *admin* as actor (never `system`), with the message stating the
  timer had not elapsed.
- **D6 — Escalation lives as an optional section of the existing expiry
  rule**, not a separate reusable "escalation policy" object. Mature tools
  (PagerDuty, Opsgenie, ServiceNow) keep escalation policies separate
  because *they* have many services referencing one policy; this tool has
  one expiry rule per tenant in the common case, so a second table/CRUD
  page/dropdown would be more configuration surface, not less.
- **D7 — At the time of writing, escalation was scoped to be honest about
  being global** (applying to *all* pending requests, not just ones matching
  the rule's app-pattern/group criteria), because the matcher had a real bug:
  an expiry rule with neither an app-pattern nor a group set was silently
  matching *nothing* rather than *everything*, and the rule-creation form
  didn't even expose those fields for expiry rules. **This has since been
  fixed** (tracked as #69, "Fix the expiry-rule matcher — appPattern and
  groupName are ignored" — now shipped and closed). **This means D7's
  stated blocker no longer exists, and per-rule scoping (only escalate
  requests matching a specific app/group) is now technically reachable.**
  This is the other open re-scoping question we want help thinking through.

**Data model (unbuilt, additive-only — see §5 for why that constraint
exists)**: `CalloutRequest` gains four nullable columns
(`assignedOwner: String`, `assignedAt: Date`, `escalatedAt: Date`,
`escalationStage: Integer`); `AutoRule` gains two
(`escalateAfterMinutes: Integer`, `claimTtlMinutes: Integer`). Notably,
`escalatedTo` (a free-text "who was notified" field) was explicitly cut —
with one stage it would always hold the literal string `"channel"`, and a
free-text description of recipients is both a duplicate of what the audit
trail already records and an invitation for a later change to leak
addresses into the log bundle. If a real per-recipient escalation stage
gets built, this column's absence is a deliberate constraint to design
around, not an oversight to "fix."

## 5. Hard constraints — apply to any proposal

- **No second source of truth for authority.** This is the single most
  load-bearing rule in this project's history. A prior feature (Slack
  inline-approval buttons driven by a `SLACK_APPROVER_MAP` config value)
  was *removed* specifically because it created a second, independently
  maintained list of "who can approve," and that list drifted from the
  real one — a revoked/removed approver kept working Slack buttons because
  nothing told the map. It **failed open**. Any delegation/escalation
  design that introduces a second place approver identity or authority
  lives (a config map, a cached snapshot, a locally-stored member list)
  needs to explain how it avoids the same failure mode, or why staleness
  there is safe (e.g., because it can only ever fail closed).
- **Never let a notification channel double as a decision channel** unless
  the click is cryptographically tied back to a verified identity the app
  already trusts (this tool *does* support Slack/Teams inline
  approve/reject today — #50/#55 — but only via signed callback payloads
  verified against a known signing secret, never via "anyone who can see
  the message"). A notify-only escalation channel is fine; a
  decide-from-the-notification channel needs the same rigor #50/#55 already
  built, not a shortcut.
- **Additive-only schema.** The database is H2 with
  `spring.jpa.hibernate.ddl-auto=update` and no migration tool. New nullable
  columns and new tables are free; drops, renames, and `NOT NULL` without a
  default are not supported and must not be proposed.
- **No new environment variables, if avoidable.** Repeatedly stated project
  preference — a new config key triggers doc updates across roughly eight
  files in this repo (README, Help page, configuration.md, docs images,
  etc.). Prefer deriving behavior from config that already exists
  (`OMNISSA_ROLE_MAP` is explicitly called out as a candidate for this).
- **The single background scheduler is shared and fragile.** All
  `@Scheduled` jobs (JIT expiry, JIT restore, the hourly expiry sweep) run
  on Spring Boot's one default scheduler thread. A wedged job silently
  stops every other job while the app's health check stays green. Any new
  scheduled work (an escalation sweep, a directory refresh) must not make
  blocking network calls without a bounded timeout, must wrap each unit of
  work so one poisoned row can't abort the whole pass, and should register
  with the existing `SchedulerHeartbeat` staleness monitor.
- **The OAuth token used for Access API calls is not cached.** A design
  that adds live Access API calls to a scheduled sweep or to every
  escalation event should either budget for "one token fetch per call" or
  treat fixing that as a prerequisite, not an afterthought.

## 6. What we want your help with

Given (a) the shipped RBAC/group-mapping foundation in §2, (b) the specific
reasoning that killed the email-escalation stage in §4/D4, and (c) the fact
that D7's blocker (the matcher bug) is now fixed:

1. **Is the group-ID-equivalence assumption (OIDC `group_ids` value ==
   SCIM `Groups/{id}`) something worth designing around, or should any
   design route around needing it at all** (e.g., resolving recipients from
   users who've previously logged in and had their own claims persisted,
   rather than doing a live group-membership lookup)? What's the strongest
   argument for each?
2. **Given "no second source of truth for authority," what's the safest
   shape for resolving a *notification* recipient list from an Access
   group** — live lookup at send time (freshest, but adds an Access API
   call to a background sweep and a new failure mode if that call is slow
   or fails), vs. capture-at-login (simpler, no live dependency, but blind
   to anyone who's never signed in), vs. something else entirely?
3. **Should D2's "no assignment picker" constraint be revisited** now that
   group-member resolution (with real names/emails) is plausible, or does
   the original reasoning (claim = self-claim only, no directory of
   assignable humans) still hold for reasons independent of "can we
   enumerate people"?
4. **Now that D7's blocker is gone, is per-rule escalation scoping (only
   escalate requests matching the rule's app-pattern/group) worth adding
   now, or should it wait for #53 (multi-stage), since a chain/stage design
   might subsume single-rule scoping anyway?** (See the sibling document if
   you want #53's design context — not required to answer this one.)
5. **Anything in D1–D7 that you think doesn't survive contact with "identity
   and groups now come wholly from Access, no local users held as the
   source of truth."** Push back on any of it if the premise it was built
   on (e.g., "OIDC users are never persisted") no longer has to be true.

Please be explicit about **what you'd need to verify against a live Access
tenant before recommending a design as final** — this project has a strong
norm of not shipping code around an unverified API assumption (see the JIT
`userId`→SCIM-id lesson in §2), and callouts to "go check X against the real
tenant" are genuinely useful output, not a cop-out.
