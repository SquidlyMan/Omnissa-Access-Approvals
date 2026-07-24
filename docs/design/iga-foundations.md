# IGA Foundations — Pre-Work Design

Status: **Approved — pre-work (#54) complete; #49 JIT BUILT & verified in
production (2026-07).** All 6 design decisions resolved. §1.2 documents the
shipped exclusion-based revocation model (both expiry modes) as verified
end-to-end against the live tenant. Covers three cross-cutting design areas that the IGA
feature tasks all depend on. Designing these once, up front, avoids retrofitting
entities and re-plumbing identity per feature.

Feature tasks these support: **#49 JIT**, **#50 Slack**, **#51 delegation/
escalation**, **#52 RBAC**, **#53 multi-stage approval**, **#55 Teams**.

Anchors in today's code:
- `model/CalloutRequest.java` — one entity per request; `state` is a free-form
  `String` (`pending`/`approved`/`rejected`/`deactivated`/`expired`), plus
  `decidedBy`, `receivedDate`, `responseDate`, `responseMessage`.
- `model/security/{UserAccount,Authority,AuthorityName}.java` — local users with
  a `ManyToMany` authority list. `AuthorityName` **already** defines
  `ROLE_ADMIN, ROLE_USER, ROLE_APPROVER`.
- `util/AuditService.currentAdmin()` — the single "who is acting" resolver
  (OIDC → local user → `system`).
- `security/SecurityConfig.java` — `permitAll` for `POST/OPTIONS
  /api/approvals/new`; two `FilterRegistrationBean`s (rate-limit + optional
  Basic auth) scoped to that one path.
- `config/RuleScheduler.java` — hourly `@Scheduled` sweep already exists.
- `spring.jpa.hibernate.ddl-auto=update` on H2 (file DB) — **additive** schema
  evolution: new nullable columns and new tables are created automatically; no
  migration tool. Drops/renames/not-null-without-default are **not** handled.

---

## 1. Data Model — designed once, additive for H2 (`ddl-auto=update`)

Design rule for every change below: **additive only** — new nullable columns on
existing tables, or brand-new tables. That keeps `ddl-auto=update` sufficient
and needs no manual migration. Anything requiring a backfill is flagged.

### 1.1 State model — shared prerequisite (#49, #53)

`CalloutRequest.state` stays a `String` (additive-friendly; the `State` enum is
already unused in persistence). Extend the vocabulary rather than the column:

| state | meaning | who sets |
|---|---|---|
| `pending` | awaiting decision | ingestion |
| `approved` / `rejected` | decided + delivered to Access | decision flow |
| `deactivated` | deactivation callout | ingestion |
| `expired` | decision undeliverable (4xx from Access) | `ApprovalsInterfaceImpl` |
| **`revoked`** *(new, #49)* | JIT access expired, user excluded in Access (re-requestable grants re-open after a hold) | scheduler |
| **`awaiting-stage`** *(new, #53)* | approved at current stage, more stages remain | chain engine |

Note the distinction the JIT feature needs: **`expired`** already means "we
could not deliver a decision"; **`revoked`** is the new, different meaning
"access was granted then later withdrawn on purpose." Keep them separate states
so audit and UI don't conflate a delivery failure with an intentional teardown.

### 1.2 #49 JIT — access expiry / TTL — **BUILT & VERIFIED in production (2026-07)**

Additive columns on **`CalloutRequest`** (all nullable):

| field | type | notes |
|---|---|---|
| `accessTtlMinutes` | `Integer` | granted TTL; nullable = permanent (today's behavior) |
| `accessExpiresAt` | `Date` | set at approval = `now + ttl` |
| `revokedAt` | `Date` | when the sweep excluded the user |
| `scimUserId` | `String` | requester's SCIM id, resolved + stored at grant (see caveat) |
| `reRequestable` | `Boolean` | Option 2 (true, default) vs Option 1 (false) — see below |
| `assignmentType` | `String` | `USER` (direct) or `GROUP` — decides the restore path |
| `restoreAt` / `restoredAt` | `Date` | Option 2 hold: when to lift / when lifted |

Flow: the approver picks a TTL (or an AutoRule supplies `grantTtlMinutes`). On
DELIVERED approval, `applyGrant` resolves + stores the SCIM id and
`assignmentType`, lifts any stale exclusion, and stamps `accessExpiresAt`.
`RuleScheduler` runs **every minute**: `applyJitExpiry` finds
`state='approved' AND accessExpiresAt < now` and **excludes** the user;
`applyJitRestore` handles the Option-2 re-open. UNREACHABLE/error retries next
minute.

#### Revocation mechanism — the exclusion model (VERIFIED end-to-end)

The approval **callout response** only *answers a pending request* — it can't
withdraw granted access. And **deleting a user entitlement only works for
directly-assigned apps**; when access comes via a **group**, there is no
per-user entitlement to delete (the original spike "worked" only because a
direct user entitlement had been hand-created). The mechanism that works for
both is a per-user **exclusion**: a `negative:true` entitlement that overrides
group access for one user *without touching the group*. All calls use the OAuth
service client (`ApprovalService`) against `…/entitlements/definitions`:

| op | call | notes |
|---|---|---|
| **read listing** | `GET …/catalogitems/{app}` | items carry `subjectType`, `subjectId`, `name`(userName), `displayName`(email), `negative` |
| **revoke** (exclude) | `PUT …/catalogitems/{app}/users/{scimId}` body `{…,"activationPolicy":"USER_ACTIVATED","negative":true}` | `POST` **409s** `entitlement.exists` when already group-entitled — **`PUT` upserts**. For a group user this creates the exclusion; for a direct user it flips their entitlement to excluded. |
| **restore, group** | `DELETE …/catalogitems/{app}/users/{scimId}` | group entitlement reapplies |
| **restore, direct** | `PUT …/users/{scimId}` body `{…,"negative":false}` | re-provisions ("User Provisioned") |

**Real deprovisioning:** ~8 s after the exclusion is applied, Access
**deactivates** the user's app instance and sends a `deactivation` callout —
i.e. the exclusion actively tears down running access, it doesn't merely block
future requests.

#### Two expiry modes (approver choice; default = re-requestable)

- **Option 1 — one-time:** at expiry, exclude and **leave excluded**; the app
  never reappears.
- **Option 2 — re-requestable (default):** at expiry, exclude (Access
  deprovisions), then after a **1-minute hold** (`restoreAt`) lift the exclusion
  via the assignment-appropriate restore above, so the app returns to a
  requestable state. `applyJitRestore` audits `access-reopened`.

The UI approval dialog surfaces this as an *"Allow the user to re-request after
expiration"* checkbox (shown only for timed grants).

#### ID mapping caveat (an Access limitation)

The callout gives the app's catalog UUID (`resourceUuid`) and Access's **numeric
`userId`** — which **cannot be mapped back to a SCIM id** (`/scim/Users/{n}`,
`externalId eq n`, and the legacy users endpoint all 404). Resolve the SCIM id
another way and **store it at grant time**: primary = the app's entitlement
listing (`matchSubjectId` — sole USERS subject, or match `name`/`displayName`);
fallback = `GET /scim/Users?filter=userName eq "…"` (email is *not* filterable
server-side). ⚠️ Pass the filter as a **URI template variable** so it is encoded
exactly once — pre-encoding double-encodes and silently matches zero users.

### 1.3 #51 delegation / escalation

Additive columns on **`CalloutRequest`** (nullable):

| field | type | notes |
|---|---|---|
| `assignedOwner` | `String` | approver identity this request is routed to (app-identity string, see §3) |
| `escalationTimeoutMinutes` | `Integer` | nullable = no escalation |
| `escalatedTo` | `String` | filled when escalation fires |
| `escalatedAt` | `Date` | when it fired |

Recommendation: **columns, not a sub-entity**, for the single-owner + single
escalation-target case (demo scope). If multi-hop escalation chains are needed
later, promote to an `Assignment` child table — but that is out of scope now and
would itself be additive (new table). `RuleScheduler` gains an
escalation sweep: `state='pending' AND assignedOwner set AND received +
timeout < now AND escalatedAt null` → set `escalatedTo` (from config/rule),
notify, audit `request-escalated`. *Additive; no backfill.*

### 1.4 #52 RBAC — role model

Roles already exist in `AuthorityName` (`ROLE_ADMIN, ROLE_USER,
ROLE_APPROVER`). **Reuse them**; do not invent a parallel enum. Proposed
canonical set for IGA:

- **ADMIN** (`ROLE_ADMIN`) — full config, rules, user mgmt, decisions.
- **APPROVER** (`ROLE_APPROVER`) — may decide requests (optionally scoped, see
  below); no config access.
- **READ_ONLY** (`ROLE_USER` reused, or add `ROLE_READ_ONLY`) — view queue +
  audit only.

Local users already carry authorities. The gap is **OIDC users**: today
`oidcUserService()` is the default `OidcUserService` and the OAuth scope is
`openid,email,profile` (no group claim) — so OIDC admins currently get only
Spring's default authorities. #52 work: request a groups scope, add a custom
`OidcUserService` that maps group claims → the `ROLE_*` authorities (mapping in
§3.3), and enforce with method/URL security (e.g. `RulesController`,
`ConfigController` → `ADMIN`; decision endpoints → `ADMIN` or `APPROVER`).

**Approver scoping** (which requests an approver sees/can act on): two options,
Dean to choose (see Open Decisions):
- **Role-only (recommended for demo):** any APPROVER can act on any request. No
  new table. Add-nothing.
- **Scoped:** new table **`ApproverScope`** `{ id, approverIdentity, matchType
  (APP|GROUP), matchValue }` filtering the queue per approver. New table =
  additive. Overlaps with #51 `assignedOwner` — keep them distinct: scope =
  "eligible to see", assignedOwner = "routed to."

*Reusing existing enum/tables = additive. If OIDC users must persist a role, no
new column needed (roles derived from claims at login).*

### 1.5 #53 multi-stage approval

Two new tables (both additive). Definition vs. per-request instance:

**`ApprovalChain`** (definition — like AutoRule, admin-managed):
```
id, name, enabled, matchAppPattern?, matchGroup?   // when this chain applies
```
**`ApprovalStage`** (ordered stages of a chain):
```
id, chainId (FK), stageOrder (int), approverType (USER|GROUP|ROLE), approverValue
```
**`ApprovalStep`** (per-request instance — one row per stage per request):
```
id, requestId (FK→CalloutRequest.requestId), stageOrder,
status (pending|approved|rejected|skipped), decidedBy, decidedAt, note
```
Plus additive columns on **`CalloutRequest`**: `chainId` (nullable — null =
today's single-stage flow) and `currentStage` (int). A request only delivers its
final decision to Access when the **last** stage approves; any stage rejection
short-circuits to `rejected`. Single-stage remains the default when `chainId` is
null, so existing behavior is unchanged.

*New tables + nullable columns = additive. No backfill* (null `chainId` = legacy
single-stage).

### 1.6 AutoRule additions (supports #49)

Additive nullable column on **`AutoRule`**: `grantTtlMinutes` — when a MATCH
rule auto-approves, it can also stamp the JIT TTL onto the request. *Additive;
no backfill.*

### 1.7 Summary — additive audit

| change | kind | backfill? |
|---|---|---|
| new states `revoked`, `awaiting-stage` | value only | none |
| `CalloutRequest`: TTL/expiry/revoke, assignment/escalation, chain/stage cols | new nullable cols | none (null = legacy) |
| `AutoRule.grantTtlMinutes` | new nullable col | none |
| OIDC group→role mapping | no schema (claim-derived) | none |
| `ApproverScope` *(if scoped)* | new table | none |
| `ApprovalChain` / `ApprovalStage` / `ApprovalStep` | new tables | none |

**Everything is additive** — `ddl-auto=update` handles it with no manual
migration and no data backfill. The only non-additive temptation to avoid:
converting `state` to an enum column or adding `NOT NULL` without a default.

---

## 2. Inbound-Endpoint Exposure Pattern (#50 Slack, #55 Teams, future webhook-ins)

New public endpoints (Slack interaction callbacks, Teams action callbacks,
email approve/reject links) must be exposed the **same** way `POST
/api/approvals/new` is — and must avoid the #48 outage, where UAG **Identity
Bridging** silently broke an unauthenticated inbound endpoint for ~10h.

### 2.1 Reverse proxy / UAG (learn from #48)

The endpoint is unauthenticated at the transport/session layer, so the gateway
must not try to assert an identity for it:

- Path must be in the proxy's **unsecure pattern** (front-end auth waived).
- **Identity Bridging MUST be OFF** for the app. Per
  `docs/troubleshooting.md` ("Behind a Unified Access Gateway (UAG): disable
  Identity Bridging"), bridging asserts an authenticated identity to the backend
  for **every** routed request; an unauthenticated callback has no identity to
  bridge, so it fails *before* reaching the app — and adding the path to the
  unsecure pattern does **not** waive bridging. This is exactly what broke the
  Access callout. Every new inbound callback is subject to the same trap.
- TLS/DNS/reachability preconditions from troubleshooting.md still apply
  (publicly resolvable, valid cert, port 443 open, probe returns 200).

### 2.2 App layer

- Add the path to the `SecurityConfig` **`permitAll`** list, exactly like
  `/api/approvals/new` (both the callback verb and any `OPTIONS` probe).
- **Authenticate the caller cryptographically, never by session or "who can see
  the message":**
  - **Slack (#50):** verify the `X-Slack-Signature` HMAC-SHA256 over
    `v0:{timestamp}:{body}` using the Slack **signing secret**; reject if the
    timestamp is older than ~5 min (replay guard).
  - **Teams (#55):** verify the HMAC in the `Authorization` header using the
    channel's shared **security token** (Teams outgoing-webhook / bot model).
  - **Email approve/reject links:** carry a **signed one-time token** (HMAC over
    `requestId + decision + expiry`, server-side secret); single-use, short TTL.
- Reuse the existing filter pattern: register the callback paths with a
  **`RateLimitFilter`** (they're internet-facing) — mirror the
  `FilterRegistrationBean` already scoped to `/api/approvals/new`.
- Decisions arriving via these endpoints flow through the **same**
  `ApprovalsInterface.requestResponse(...)` path and set `decidedBy` to the
  resolved caller identity (§3).

### 2.3 Reusable checklist — every new inbound public endpoint

1. **Proxy:** path in unsecure pattern **AND Identity Bridging OFF** for the app.
2. **App:** add path (and `OPTIONS`) to `SecurityConfig` `permitAll`.
3. **Caller auth:** HMAC signature (Slack/Teams) or signed one-time token
   (email) verified in a filter/handler — **before** any state change. Reject on
   bad/missing/expired signature.
4. **Replay guard:** timestamp/nonce window; one-time tokens single-use.
5. **Rate-limit:** register the path with `RateLimitFilter` (internet-facing).
6. **Never** rely on session/network trust or message visibility for authz.
7. **Audit:** record the inbound decision with the resolved identity (§3).

---

## 3. Approver-Identity Threading (unifies #50 / #51 / #52 / #53)

Define **one** "who decided" model so identity is consistent across all
channels, instead of each feature inventing its own attribution. Today
`AuditService.currentAdmin()` already resolves OIDC user / local user / `system`
from the Spring `SecurityContext`. Generalize that into a small resolved-identity
abstraction that also covers callers with **no** Spring session (Slack, email,
per-stage).

### 3.1 `ResolvedIdentity` abstraction

A tiny value object produced by an `IdentityResolver`:
```
ResolvedIdentity { displayName, source, role? }
  source ∈ { OIDC, LOCAL, SLACK, TEAMS, EMAIL_TOKEN, RULE, SYSTEM }
```
Resolution sources:
- **Interactive UI** (today) — from `SecurityContextHolder`
  (`OidcUser`→preferred_username/email, `UserAccount`→username). This is exactly
  the current `currentAdmin()` logic, moved behind the resolver.
- **Slack (#50)** — verified Slack user id → mapped to an app approver identity
  and role (§3.3); `source=SLACK`.
- **Teams (#55)** — same shape, `source=TEAMS`.
- **Scheduled / auto (#49 revoke, expiry rules)** — `system` or `rule:#<id>`;
  `source=SYSTEM`/`RULE` (matches today's `system` return).
- **Per-stage approver (#53)** — whichever identity decided a given stage, from
  any of the above sources.

`AuditService.currentAdmin()` becomes a thin wrapper over
`identityResolver.resolve().displayName()` so existing callers are unchanged
(back-compatible; the `AuditServiceCurrentAdminTest` contract holds).

### 3.2 Where the identity flows

One resolved identity string threads through every sink that exists today, plus
the new per-stage record:

| sink | field | today |
|---|---|---|
| audit trail | `AuditEvent.adminUsername` | `AuditService.record()` |
| decision webhook | `decidedBy` (payload) | `WebhookNotifier.notifyDecision(..., decidedBy, ...)` |
| request record | `CalloutRequest.decidedBy` | set in `ApprovalsInterfaceImpl` |
| **per-stage (#53)** | `ApprovalStep.decidedBy` | *new* |

Because all four already consume a single `String`, threading a
`ResolvedIdentity.displayName()` requires no signature churn — the decision
endpoints just pass the resolved value instead of calling `currentAdmin()`
directly for non-session callers.

### 3.3 Mapping rules

- **OIDC group claim → role (#52):** add a groups scope to the OAuth
  registration and a custom `OidcUserService` that reads the group claim and
  maps to authorities. Proposed default mapping (Dean to confirm group names):

  | Access group claim | app role |
  |---|---|
  | `Access-Approval-Admins` | `ROLE_ADMIN` |
  | `Access-Approval-Approvers` | `ROLE_APPROVER` |
  | *(any other authenticated user)* | `ROLE_USER` (read-only) |

- **Slack/Teams user → app approver:** maintain a small config map
  `slackUserId → appIdentity(+role)` (env/config for the demo; a table later if
  needed). Unmapped chat users are rejected — a valid HMAC proves the message
  came from the workspace, **not** that the clicking user is an authorized
  approver, so channel membership alone must never grant decision rights.

---

## Resolved decisions (2026-07-22)

All six confirmed by Dean. These are the design baseline for the feature builds (#49–#53, #55).

1. **RBAC role set** — REUSE existing `AuthorityName` (`ROLE_ADMIN` = ADMIN, `ROLE_APPROVER` = APPROVER, `ROLE_USER` = READ_ONLY). No new role enum.
2. **OIDC group → role mapping** — map Access group claims to roles; add a `groups` scope/claim to the Access OIDC client (today it sends only `openid email profile`). *Still needed: the actual Access group names → which map to ADMIN vs APPROVER (collect at #52 build time).*
3. **Approver scoping** — ROLE-ONLY. Any APPROVER may act on any request; one shared queue. No `ApproverScope` table. Targeted routing comes from #51 (delegation owner) and #53 (per-stage approvers) instead.
4. **Escalation shape (#51)** — COLUMNS on `CalloutRequest` (`assignedOwner`, `escalationTarget`, `escalationTimeout`, `escalatedAt`). Single hop (assignee + backup). No `Assignment` sub-entity.
5. **JIT default TTL (#49)** — NULL = permanent. TTL is always explicit: a review-dialog picker (Permanent / 1h / 8h / 7d / custom) and `AutoRule.grantTtlMinutes`. Normal approvals stay permanent; JIT is opt-in per approval/rule.
6. **Chat-user identity trust (#50/#55)** — REQUIRE an explicit Slack/Teams-user → app-approver mapping. Never trust any workspace member who can see the message.

<!-- Original open-decisions list, now resolved above:


1. **RBAC role set.** Reuse existing `AuthorityName` (`ROLE_ADMIN`,
   `ROLE_APPROVER`, `ROLE_USER`) as ADMIN / APPROVER / READ_ONLY, or add a
   distinct `ROLE_READ_ONLY`? (Recommend reuse.)
2. **OIDC group → role mapping.** Confirm the actual Access group names and
   which map to ADMIN vs APPROVER (table in §3.3 is a placeholder). Also confirm
   we can add a groups scope/claim on the Access OIDC client.
3. **Approver scoping.** Role-only (any APPROVER acts on any request — no new
   table, recommended for the demo) **or** the scoped `ApproverScope` table
   (§1.4)?
4. **Escalation shape (#51).** Columns on `CalloutRequest` (single owner +
   single escalation target, recommended) vs. an `Assignment` sub-entity for
   multi-hop chains?
5. **JIT default TTL (#49).** Is there a sensible default TTL, or is null =
   permanent the default and TTL always explicit per approval/rule?
6. **Chat-user identity trust (#50/#55).** Confirm we require an explicit
   Slack/Teams-user → app-approver mapping (recommended) rather than trusting any
   workspace member who can see the message.
-->
