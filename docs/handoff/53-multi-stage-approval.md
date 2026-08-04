# Handoff brief — #53 Multi-Stage Approval

> ## ⚠️ STATUS: BUILT AND SHIPPED — this is now a historical record
>
> **This document was written as a design brief, before the feature existed.
> It has since been built and deployed.** Multi-stage approval chains shipped
> in **v1.20.0** (2026-08-02); named-individual stages and a transaction fix
> followed in **v1.21.0** (2026-08-03).
>
> It is kept because the *reasoning* is still useful — the constraints in §5
> and the prior art in §6 shaped what was built and would shape any change to
> it. But **§4 describes a sketch that was only partly built, and §7's
> questions are answered**, with the actual resolutions recorded in §8 at the
> end. Where the two disagree, §8 wins.
>
> **For how the feature actually behaves, read these instead:**
> - `docs/approval-chains.md` — the user-facing guide
> - `docs/publish/documentation.md` §2.7 — the published reference
> - The in-app Help page, section "Approval Chains"
> - `ApprovalChain` / `ApprovalStage` / `ApprovalChainService` javadoc, which
>   carries the per-decision reasoning

**Original purpose**: a self-contained brief for another LLM (no access to
the codebase assumed), giving full project context, the state of the feature
at the time, and the open questions we wanted help reasoning through. A
sibling document, `51-delegation-escalation.md`, covers a related but
separate feature (claim/release + timeout escalation on a *single*-stage
request) — that one has also since shipped, in v1.21.0.

---

## 1. What this tool is

**Access Approval Tool for Omnissa** — a self-hosted approval gateway that
sits in front of Omnissa Access (formerly VMware Workspace ONE Access), an
identity/SSO/app-catalog product. One Spring Boot 4 (Java 17) backend + one
React SPA, shipped as a single Docker container with an embedded H2 file
database. It is an unsupported personal lab project (MIT license,
non-production), deployed on a home NAS, one Access tenant per deployment.
Public repo: `github.com/SquidlyMan/Omnissa-Access-Approvals`.

**The problem it solves**: Omnissa Access can require admin approval before
a user is granted an application. Access's own built-in approval workflow is
thin, so this tool receives Access's approval webhook ("callout"), shows the
request in a queue, lets a human approve or reject it, and posts the
decision back to Access through its API. Optional extras layered on since:
auto-rules (pattern-match auto-approve/reject/expire), Slack/Teams
notifications with inline approve/reject, JIT/time-bound access grants with
automatic revocation, an audit trail, syslog export.

**Core request lifecycle today (single decision, no chain)**:

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
`revoked`. A new state, `awaiting-stage`, is reserved (named, not yet used)
for exactly this feature: "approved at the current stage, more stages
remain before Access gets a final answer."

> **As built, `awaiting-stage` was never used.** A chained request stays
> `pending` for the whole chain, with `currentStage` carrying the progress.
> That keeps it visible in the existing queue view with no UI change, and
> avoids a new state value that every state-filtering query would have had to
> learn about. See §8.

**Version at the time of writing**: 1.19.12, with #53 unbuilt — a data-model
sketch in a shared pre-work design doc and nothing else. **It shipped in
1.20.0**; the current version is 1.21.0.

## 2. Identity and authorization model — where it stands today

**Already shipped (#52, RBAC via Access groups)**:

- The tool can run with **zero local user accounts**. OIDC login ("Sign in
  with Omnissa Access") is fully supported, and local username/password
  login can be disabled entirely.
- Roles are `ROLE_ADMIN`, `ROLE_APPROVER`, `ROLE_VIEWER`, `ROLE_AUDITOR`
  (plus a legacy `ROLE_USER` alias for `ROLE_VIEWER`), **additive** — a user
  in two mapped groups gets the union of both roles' capabilities.
- Mapping is configured as `OMNISSA_ROLE_MAP = <groupId>:<ROLE>,...` —
  **group IDs, not names**, matched against the OIDC `group_ids` claim
  (sourced from the ID token, or via an automatic userinfo round-trip once a
  user is in more than ~20 groups — Access moves large claims behind an
  `ovc`/`ovl` overflow indirection that Spring's OIDC client resolves
  transparently).
- Anyone who signs in via OIDC but matches no configured group gets
  `ROLE_VIEWER` by default — a deliberate fallback, but worth knowing:
  effective access control is only as tight as the OIDC client's Access
  access-policy scope plus the completeness of the group map.
- Today, **`APPROVER` is a flat, undifferentiated role** — any user with
  `ROLE_APPROVER` can decide any pending request; there is no concept of
  "this approver is only eligible for this specific request." That's true
  project-wide today, and is a deliberate resolved decision (see §5). A
  multi-stage chain is the first feature that needs a *narrower*
  per-request, per-stage notion of "who is allowed to act here" — which is
  new ground for this codebase, not an extension of something that already
  exists.

**NOT yet used anywhere in the app**: SCIM. No code today resolves an Access
*group* to its member list or their contact attributes (email, phone, UPN,
etc.) — the only SCIM-adjacent lookup that exists is JIT's (#49)
requester-id resolution at grant time, which is a different problem (mapping
one already-known person to their SCIM id, not enumerating a group's
members). `OmnissaRestClient`, which all outbound Access API calls go
through, fetches a brand-new OAuth token on **every single call** — no
caching, no pooling. That's a real prerequisite to fix before any design
that resolves group membership on the approval hot path (e.g., "who is
eligible to act on stage 2" needs to be answered fast, possibly on every
page load of the queue).

> **Both of these were addressed before the feature was built.**
> `OmnissaRestClient` now caches its OAuth token per tenant (1.20.0), and
> `AccessGroupService` resolves a group's members live via SCIM, never cached
> or persisted — see §8.

**The open, unverified assumption that affects this feature directly**: is
a group ID in `OMNISSA_ROLE_MAP` (and the OIDC `group_ids` claim) the same
id space as a SCIM `Groups/{id}`? If yes, a stage's `approverType=GROUP`
could resolve live against Access with no new admin configuration. If no,
you need either a second, separately-maintained group reference, or a
design that avoids needing group-membership resolution at all (e.g., a
stage that just checks "does the acting user hold this role," never asking
who *else* holds it). **This project was already burned once by a
structurally identical assumption** — a JIT feature assumed the callout's
numeric Access `userId` could be mapped to a SCIM id directly, and it
cannot (`/scim/Users/{n}`, `externalId eq n`, and the legacy users endpoint
all 404 on it; the actual resolution path is indirect, via entitlement
listings). Treat "verify this against the live tenant before designing
around it" as a real requirement, not boilerplate caution.

> **VERIFIED TRUE, 2026-08-02, against the live tenant.** `GET
> /SAAS/jersey/manager/api/scim/Groups/{id}` returned `200` for a group id
> taken straight out of `OMNISSA_ROLE_MAP`, with the matching `displayName`.
> The id spaces are the same, so `approverType=GROUP` resolves with no new
> admin configuration. Member records also carry `emails[0].value` and the
> workspace-extension `userPrincipalName`; **no phone/mobile attribute was
> observed**, which is why `GroupMember` has no field for one.

## 3. The problem #53 exists to solve

Today every request gets exactly one decision from exactly one approval
tier. Some organizations want a request to require sequential sign-off —
e.g., a team lead approves, then IT approves, then it's granted — where a
rejection at any stage ends the request and only the final stage's approval
actually tells Access to grant access. Nothing like this exists today; #51
(claim/release/escalation) operates entirely within a single decision, not
across a sequence of them.

## 4. What's already decided (very early sketch, unbuilt)

The only existing material is §1.5 of a shared pre-work document
(`docs/design/iga-foundations.md`) that scoped the *data model* for several
IGA features at once, specifically to avoid retrofitting entities per
feature. It is explicitly a sketch, not a reviewed design — unlike #51, it
has not been through independent review. Take it as a reasonable starting
shape, not a constraint to defend.

**Proposed schema (three new tables, all additive)**:

- **`ApprovalChain`** (definition, admin-managed like an auto-rule):
  `id, name, enabled, matchAppPattern?, matchGroup?` — describes *when* this
  chain applies to an incoming request.
- **`ApprovalStage`** (ordered stage definitions belonging to a chain):
  `id, chainId (FK), stageOrder (int), approverType (USER|GROUP|ROLE), approverValue`
  — describes *who* can decide at each step. `approverType=ROLE` is the one
  that maps cleanly onto what already exists (`ROLE_APPROVER`, etc.);
  `approverType=GROUP` is the one that depends on the open SCIM
  group-resolution question in §2; `approverType=USER` implies persisting
  or otherwise being able to name individual people, which runs into the
  same "no directory of assignable humans" issue #51's D2 hit.
- **`ApprovalStep`** (one row per stage per in-flight request — the runtime
  instance, distinct from the definition above):
  `id, requestId (FK), stageOrder, status (pending|approved|rejected|skipped), decidedBy, decidedAt, note`.
- **On `CalloutRequest`**: two new nullable columns, `chainId` and
  `currentStage`. `chainId = null` means "no chain — today's single-decision
  behavior, unchanged." This is the backward-compatibility hinge: existing
  installs and existing requests are simply never routed into a chain unless
  one is configured and matched.
- **Decision rule**: a request only delivers its final decision to Access
  when the **last** stage approves. Any stage's rejection short-circuits the
  whole request straight to `rejected` — no partial credit, no "reject at
  stage 2 goes back to stage 1."
- **Matching**: `matchAppPattern`/`matchGroup` on `ApprovalChain` is
  explicitly modeled on the existing `AutoRule` matcher fields. Worth
  knowing: those *exact* fields on auto-rules had a real, since-fixed bug
  (tracked as #69) where a rule with neither field set silently matched
  *nothing* rather than *everything*, and the admin form didn't expose the
  fields for one rule type at all. The fix is shipped and the matcher now
  correctly treats "no criteria" as "matches everything" — but if
  `ApprovalChain` reuses this matching code (recommended, rather than
  reinventing it), make sure the fix's semantics carry over rather than
  re-introducing the old bug in a new matcher implementation.

**What the sketch does not address at all** (genuinely open, not just
under-specified): how a stage's eligible approvers get *notified*; how
escalation (§ below) interacts with a stage that nobody acts on; what
happens to JIT TTL semantics when the "approval" that triggers a TTL grant
is stage 3 of 3 rather than the only decision; how the admin UI presents
"who can act on this specific pending request" when today the queue treats
every `APPROVER` as uniformly eligible for everything; and how a chain
definition (`ApprovalChain`/`ApprovalStage`) gets authored in the UI at all
(no form/page has been designed).

## 5. Hard constraints — apply to any proposal

- **No second source of truth for authority.** The single most load-bearing
  rule in this project's history. A prior feature (Slack inline-approval
  buttons driven by a separately-maintained approver map) was *removed*
  because that second list drifted from the real one and **failed open** —
  a revoked/removed approver kept working Slack buttons. Any design that
  determines "who can act on stage N" from something other than a live
  check against Access-sourced roles/groups (a cached snapshot, a
  copy-on-create member list frozen into `ApprovalStep`, etc.) needs to
  explain how staleness there can only ever fail *closed*, not open. This
  cuts directly against a tempting implementation shortcut: resolving a
  stage's group membership *once*, at chain-match time, and freezing it
  into the `ApprovalStep` row — convenient, but means someone added to the
  approver group after the request arrived can never act on it, and someone
  removed from it can act on it anyway if they were captured before being
  removed.
- **Approver scoping is currently role-only, by explicit resolved
  decision.** `iga-foundations.md`'s resolved-decisions log states: "any
  APPROVER may act on any request; one shared queue; no `ApproverScope`
  table," with the explicit note that *targeted* routing was deliberately
  deferred to #51 (delegation owner) and #53 (this feature, per-stage
  approvers) instead of being built as a general scoping mechanism. That
  means #53 is where "not every approver can act on every request" enters
  the codebase for the first time — there is no existing per-request
  eligibility-check code path to extend; it has to be designed from
  scratch, and needs to compose with #51's existing "any APPROVER, always"
  default for non-chained requests without regressing it.
- **Additive-only schema.** H2 with `ddl-auto=update`, no migration tool.
  New nullable columns and new tables only — no drops, renames, or `NOT
  NULL` without a default.
- **No new environment variables, if avoidable.** A stated project
  preference — a new config key triggers doc updates across roughly eight
  files in this repo. Chain/stage definitions belong in the database (like
  auto-rules), administered through a UI, not environment variables — this
  one's less likely to be violated by a chain feature than by #51, but
  worth stating.
- **The single background scheduler is shared and fragile.** All
  `@Scheduled` jobs run on Spring Boot's one default scheduler thread. If
  #53 needs any timed behavior (a stage-level escalation/timeout, mirroring
  #51's), it must not make blocking network calls without a bounded
  timeout, must isolate failures per-request, and should register with the
  existing scheduler-staleness monitor. If #53's stage timeouts end up
  reusing #51's escalation sweep rather than adding a second one, that's
  probably the right call — a second competing scheduled job is exactly the
  kind of thing that's bitten this project before (a wedged job silently
  stops the others while health stays green).
- **The OAuth token used for Access API calls is not cached.** Any design
  that resolves group membership live (per §2) on a path that runs
  per-request or per-page-load needs to either budget for that or treat
  token caching as a prerequisite.

## 6. Prior art (use with caution — flagged below)

Earlier research surfaced Microsoft Entra ID's Entitlement Management / PIM
approval model as the closest commercial analog: multi-stage chains capped
at a small fixed number of stages (observed as 3), "alternate approvers"
configured *per stage* as the actual escalation mechanism (rather than
escalating to a different stage or a different policy), a
fallback-on-**resolution-failure** semantic (if the configured approver for
a stage can no longer be resolved — e.g., left the org — an alternate is
used) that is notably *different* from fallback-on-**timeout** (a stage that
times out with no decision is treated as an automatic **denial**, not an
automatic escalation), and an approver-initiated delegation feature that is
explicitly one-level-only and does **not** apply to group-assigned
approvers (you can delegate your own individual approval duty; you cannot
delegate a group's).

**Caveat on this section**: this was gathered by an earlier research pass in
this project and has not been independently re-verified against Microsoft's
current documentation by the author of this brief. Treat the specific
claims above (stage cap, exact fallback semantics) as *directionally
useful* — a real product converged on "timeout ≠ escalation, they're
different failure modes with different correct responses" and "delegation
doesn't compose with group-based approval" for reasons probably worth
understanding — rather than as verified facts to cite. If you have your own
knowledge of Entra ID PIM/Entitlement Management, ServiceNow's
`sysapproval_approver`/multi-stage `Flow Designer` approvals, Saviynt, or
SailPoint's approval chains, independent input on how those systems handle
the same questions (§7 below) is exactly what's wanted here — this section
should not be treated as the ceiling of relevant prior art, just what
happened to get looked at first.

## 7. What we wanted help with *(all resolved — see §8)*

1. **How should a stage resolve "who is eligible to act right now,"
   consistent with "no second source of truth for authority"?** Live check
   against Access on every queue render/decision attempt (correct, but adds
   an Access API call — see the token-caching constraint) vs. some cached/
   session-scoped version (faster, but needs an explicit story for why
   staleness can't cause a wrongly-authorized or wrongly-blocked decision)?
   Does the answer differ for `approverType=ROLE` (cheap — it's just "does
   this already-authenticated user's session carry this role," no group
   lookup needed) vs. `approverType=GROUP` (needs the SCIM
   group-membership-resolution capability that doesn't exist yet, and
   depends on the unverified id-space assumption in §2)? Given that, **is
   `approverType=USER` worth keeping in the design at all**, or should it be
   cut the same way #51 cut its "assign to a specific person" picker, for
   the same underlying reason?
2. **Timeout vs. escalation, per stage — pick a default, and justify it
   against this tool's existing behavior.** Entra ID PIM treats
   no-decision-in-time as automatic denial, not escalation (see §6). This
   tool's existing single-stage flow (#51) treats no-decision-in-time as
   "nudge a channel, no auto-decision, the *separate* expiry auto-rule is
   what eventually auto-rejects." Should a stage in a chain inherit #51's
   escalation-then-eventual-expiry model unmodified, or does having
   multiple stages change the right default (e.g., because a stuck stage 1
   of 3 is a worse outcome to silently expire than a stuck single-stage
   request, since more approvers' time may already be invested)?
3. **Should chain-level escalation reuse #51's `AutoRule`-attached
   escalation sweep, or does it need its own?** Given the shared-scheduler
   fragility constraint in §5, and that #51 already claims the "escalate
   after N minutes pending" concept at the `AutoRule` level — does a
   per-stage timeout even belong on `ApprovalStage`, or should it be
   expressed by pointing each stage at an existing (or new) `AutoRule`-style
   policy, reusing #51's mechanism instead of duplicating it?
4. **What's the smallest viable UI for authoring a chain?** No design work
   has happened here at all. Given this tool's existing pattern for
   admin-configured policy (the auto-rules list/form, which is a single
   flat list with inline validation, no nested resource editor), what's a
   reasonable v1 for defining an ordered list of stages without building a
   heavyweight workflow designer?
5. **How does this interact with JIT (#49) and auto-rules?** JIT's TTL is
   currently stamped at the moment a request is approved. In a chain, does
   TTL apply only at final-stage approval (the only point access is
   actually granted), and should intermediate-stage approvals be
   JIT-agnostic entirely? Separately: today a MATCH auto-rule can
   auto-approve a request the instant it arrives, before any human sees it
   — should a chain-matched request be exempt from auto-rules entirely (a
   chain implies "this needs sequential human judgment" by definition), or
   should an auto-rule be able to auto-approve *a single stage* of a chain?
6. **Anything about the `ApprovalChain`/`ApprovalStage`/`ApprovalStep`
   three-table split in §4 that you'd restructure**, given everything above
   — e.g., does per-stage escalation config belong on `ApprovalStage`
   (the definition) or does it need its own timing state on `ApprovalStep`
   (the instance)?

Please be explicit about **what you'd need to verify against a live Access
tenant before treating a design as final** — this project has a strong norm
of not shipping code around an unverified API assumption (see the JIT
`userId`→SCIM-id lesson in §2), and "go check X against the real tenant" is
genuinely useful output here, not a cop-out.

---

## 8. What was actually built — resolutions (added 2026-08-03)

Shipped in **v1.20.0**, extended in **v1.21.0**. Where this section
contradicts §4 or §7, this section is correct.

### Answers to §7's questions

**1. Eligibility resolution.** Split by type, as the question suspected.
`ROLE` is a cheap local check against the acting session's granted
authorities — no Access call. `GROUP` is a **live** `AccessGroupService`
lookup, never cached and never persisted: staleness there could authorize
somebody who had just been removed from the group, which is the "second
source of truth for authority" failure this project already removed a
feature over. If Access is unreachable the call returns empty and the stage
fails **closed**. `ROLE_ADMIN` always passes, as break-glass.

**2. `approverType=USER` — cut, then added.** Initially cut for exactly the
reason the question suggests (no reliable way to enumerate individuals).
Added in **v1.21.0** on explicit request, but resolved differently from the
original sketch: it does *not* enumerate anybody. It matches the acting
session's own identity — preferred_username, email or subject for OIDC, the
username for a local account — so it needs no directory call and, unlike a
`GROUP` stage, **works for local accounts**. It is the narrowest stage type
and therefore the only one that goes undecidable when that person leaves;
the admin override is what keeps that from being a dead end, and the
javadoc says so.

**3. Per-stage timeout — not built.** A stuck stage is covered only by the
whole-request expiry rule, exactly like an unstaged request. Deliberately
deferred rather than guessed at, and #51's escalation (shipped v1.21.0) is
the mechanism it should reuse if it is ever added — not a duplicate one.

**4. Chain-level escalation — not built**, same reasoning. #51's escalation
attaches to the `AutoRule`, and nothing in a chain points at one yet.

**5. UI.** A `ChainsPage` modelled on the existing Auto-Rules page: a flat
chain list, each expandable to an ordered stage editor with add / remove /
reorder and a single "Save Stages" that replaces the whole list. Stage
*order* is assigned server-side from array position, so gapped or duplicate
ordering cannot be submitted at all.

**6. JIT and auto-rules.** TTL applies only at final-stage approval, because
that is the only point access is actually granted; intermediate stages never
contact Access. A chain-matched request is **fully exempt** from auto-rules
— a chain exists to require sequential human judgment, so letting a MATCH
rule auto-decide it on arrival would defeat the point. Chains are evaluated
first; if one matches, auto-rules are skipped entirely.

**7. Three-table split — reduced to two.** `ApprovalStep` (the per-request,
per-stage instance rows) was **dropped**. The audit trail already records
every stage decision with its decider and timestamp, so a parallel table
would have been a second home for the same facts. `CalloutRequest` carries
`chainId` + `currentStage` instead. The cost is honest: there is no
queryable per-stage history table, so "show me stage 2's decision" means
reading the audit trail.

### Also worth knowing

- **`awaiting-stage` was never used.** A chained request stays `pending`
  throughout; `currentStage` carries progress. Avoids teaching every
  state-filtering query a new value.
- **Bulk "decide all pending" skips chained requests** — deciding a specific
  stage's approver cannot be a bulk action by definition.
- **`DecisionOutcome` gained `STAGE_ADVANCED`** for "approved, but not the
  final stage, so nothing was sent to Access."
- **A chain with no stages is never matched**, logged when skipped: it would
  create a request nobody is eligible to decide.
- **Rejection at any stage short-circuits** the whole request immediately.

### Bugs this feature shipped with, since fixed

- **Saving a chain's stages worked once and 500'd every time after** (fixed
  v1.21.0). `deleteByChainId` is a derived delete needing a transaction that
  nothing supplied, so the outcome depended on whether there was anything to
  delete — the first save found no rows and appeared to work. Deleting a
  chain that had stages failed identically. The delete and re-insert are now
  also atomic; a failure between them would have left a chain with no
  stages, which silently matches nothing.
- **Five audit actions rendered grey** for want of a registered frontend
  style, including this feature's `chain-matched` and `stage-approved`.
