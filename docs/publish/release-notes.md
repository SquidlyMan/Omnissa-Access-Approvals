---
title: "Access Approval Tool for Omnissa"
subtitle: "Release Notes — v1.21.0 and complete version history"
author: "Dean Flaming (SquidlyMan)"
date: "MIT License"
---

![](assets/logo.png){.logo width="0.52in"}

**Repository:** <https://github.com/SquidlyMan/Omnissa-Access-Approvals>
**Container:** `ghcr.io/squidlyman/omnissa-access-approvals`

> ### ⚠️ UNSUPPORTED — NON-PRODUCTION USE ONLY
>
> **This is not an Omnissa product and is not supported by Omnissa.** It is
> provided **as-is, without warranty of any kind**, for testing and
> demonstration of Omnissa Access application approvals.
>
> **Do not use in production or with production data.** The tool modifies real
> application entitlements in whichever tenant it is configured against.
>
> **All use is entirely at your own risk.** "Omnissa" and "Workspace ONE" are
> trademarks of Omnissa, LLC, used here only to describe interoperability.

---

## How to read these notes

Each release lists **Key Capabilities**, **Fixes** and **Known Issues**.

*Known Issues* are recorded honestly rather than retrospectively invented. For
current releases they are live limitations. For historical releases they name a
defect that was present in that build and say which release resolved it — so a
reader on an older version can tell what they are exposed to and what upgrading
buys them. Where nothing was recorded, the entry says so rather than implying a
clean bill of health.

Entries marked **⚠ Upgrade note** change configuration or remove an endpoint,
and need action beyond pulling a new image.

### Versions and container tags are not the same thing

A **version** is a unit of change. A **tag** is a published container image.
Several versions often ship inside one image, so the tag list is shorter than
the version list — there is no `1.7.1` image, for example, because that work
reached users inside `v1.9.1`.

The right-hand column below gives the image each version first shipped in. Pull
that tag to get everything at or below it. The mapping is stated explicitly
because it cannot be reconstructed from the repository history: the changelog
was backfilled, so versions 1.5.0 through 1.9.1 were written up only after
`v1.9.5` was already published.

---

## Release summary

| Version | Theme | First shipped in |
|---|---|---|
| **1.21.0** | Ownership and escalation; named-person chain stages | `v1.21.0` |
| **1.20.0** | Multi-stage approval chains, Hub Notifications | `v1.20.0` |
| **1.19.12** | Help documents the credential-save step that makes callout auth take effect | `v1.19.12` |
| **1.19.11** | A challenge is not a failure: callout logging corrected | `v1.19.11` |
| **1.19.10** | Idempotent callout ingest — Access delivers from multiple nodes | `v1.19.10` |
| **1.19.9** | Callout authentication engages: the OPTIONS probe is challenged | `v1.19.9` |
| **1.19.8** | Diagnostic switch for the OPTIONS probe | `v1.19.8` |
| **1.19.7** | A rejected callout reports what the caller sent | `v1.19.7` |
| **1.19.6** | Documentation accuracy for mandatory callout authentication | `v1.19.6` |
| **1.19.5** | Callout endpoint hardening: forgeable rate-limit key, anonymous ingest | `v1.19.5` |
| **1.19.4** | Bounded SMTP timeouts | `v1.19.4` |
| **1.19.3** | Expiry rules honour their own criteria | `v1.19.3` |
| **1.19.2** | SPA route fallback, declared paged contract | `v1.19.2` |
| **1.19.1** | Log-noise and routing fixes | `v1.19.1` |
| **1.19.0** | Sign-in throttling, configurable password policy | `v1.19.1` |
| **1.18.0** | Operability: health, drift detection, account management | `v1.18.0` |
| **1.16.1** | Access governance: RBAC from Access groups | `v1.16.1` |
| **1.9.5** | ZimaCube tile fix, corrected CasaOS update guidance | `v1.9.5` |
| **1.9.1** | Webhook URL encoding fix | `v1.9.1` |
| **1.9.0** | Actionable Microsoft Teams approvals | `v1.9.1` |
| **1.8.0** | On-demand revoke, deployment-type preservation | `v1.9.1` |
| **1.7.2** | Slack rendering fixes | `v1.9.1` |
| **1.7.1** | Slack reject-and-block | `v1.9.1` |
| **1.7.0** | Permanent vs temporary decline | `v1.9.1` |
| **1.6.0** | JIT lifecycle notifications | `v1.9.1` |
| **1.5.7** | Actionable Slack approvals | `v1.9.1` |
| **1.5.0** | JIT / time-bound access, backup and restore | `v1.5.6` |
| **1.4.0** | Platform upgrade: Spring Boot 4, React 19, Tailwind 4 | `v1.5.6` |
| **1.3.0** | Pull from Access | `v1.5.6` |
| **1.2.1** | Configurable email sender | `v1.5.6` |
| **1.2.0** | Expired-request handling | `v1.5.6` |
| **1.1.1** | Optional Watchtower auto-update | `v1.5.6` |
| **1.1.0** | Decision webhooks, named attribution | `v1.5.6` |
| **1.0.0** | Initial public release | `v1.0.0` |

Published images: `v1.21.0`, `v1.20.0`, `v1.19.12`, `v1.19.11`, `v1.19.10`, `v1.19.9`, `v1.19.8`, `v1.19.7`, `v1.19.6`, `v1.19.5`, `v1.19.4`, `v1.19.3`, `v1.19.2`, `v1.19.1`, `v1.18.0`, `v1.16.1`, `v1.9.5`, `v1.9.1`,
`v1.5.6`, `v1.0.0` — plus moving `major.minor` and `latest` tags.

For everything added since v1.2 grouped by capability rather than by release,
see the companion **Feature Summary** document.

---

# What's New in Access Approval Tool v1.21.0

### Key Capabilities in this release

- **Ownership — claim, assign, release.** An approver can take visible
  ownership of a pending request, hand it to a named approver, or release it
  back to the pool. **Ownership is advisory and never authorization:** any
  approver can decide any request no matter who holds it, because a claim that
  could block a decision would make a request undecidable the moment its owner
  went on leave. Claiming does not steal somebody else's claim, but any
  approver may release any claim, so a request is never welded to somebody who
  has left. The Assign picker resolves live from the Access groups already
  mapped to the Approver and Admin roles — there is no second approver list to
  maintain.
- **Escalation.** Configured on an expiry rule, so one rule reads *"nudge after
  4 hours, then auto-reject after 3 days"*. A request pending past the
  threshold notifies the chat channel **and** pushes a Hub Notification to the
  approvers themselves, honouring the rule's own application-name pattern and
  group. **Escalate now** skips the remaining timer — the only way to confirm a
  rule is wired up correctly without waiting — and is audited as the
  administrator rather than as the timer. An unactioned claim is auto-released,
  because an abandoned claim reads as "handled" to everyone else, which is a
  worse signal than no claim at all.
- **Approval chain stages can name one individual**, alongside a role or an
  Access group. Matched against the acting session's own identity, so unlike a
  group stage it also works for local accounts.

### Fixes

- **Saving a chain's stages worked once and failed every time after.** The
  delete behind the rewrite needed a transaction and did not have one, so the
  outcome depended entirely on whether there was anything to delete: the first
  save found no existing stages and appeared to work, while every save after it
  returned *Internal Server Error*. Deleting a chain that had stages failed the
  same way, having only ever been exercised on empty chains.
- **The Chains editor gave no feedback.** Unsaved edits, a successful save and
  a failed save were indistinguishable — all three looked like nothing
  happening.
- **Five audit actions rendered grey** for want of a registered style, three
  from this release and two that 1.20.0 added to the backend without adding to
  the interface.

### Known Issues

- Escalation is a single stage. There is no second stage, no email escalation,
  and no per-stage SLA inside an approval chain.
- A named-person chain stage becomes undecidable if that person leaves or
  changes how they sign in. Administrators can always decide any stage, which
  is what stops that being a dead end.

---

# What's New in Access Approval Tool v1.20.0

### Key Capabilities in this release

- **Multi-stage approval chains.** A chain requires sequential approval by
  different stages — matched by application-name pattern and/or Access group —
  before a request reaches Omnissa Access, instead of any one approver deciding
  it outright. Each stage requires either anyone holding a role or anyone in a
  specific Access group. Approving a non-final stage never contacts Access; the
  request stays pending and advances to the next stage. Rejecting at any stage
  rejects the whole request immediately. Administrators may always decide any
  stage — the same break-glass precedent used elsewhere in the tool — so a chain
  whose stage requirement can no longer be satisfied is never a dead end.
  Managed on the new **Chains** page. A chain-matched request is exempt from
  Auto-Approval Rules.
- **Hub Notifications**, as an additional notify-only delivery channel.
  Whoever is eligible for a chain's current stage is pushed a notification when
  a request enters a chain and after every stage advance, if the tenant has Hub
  Notifications enabled. It never carries an action button — every decision
  still happens by signing in to the tool, the same reasoning that keeps
  Slack/Teams approvals as deep links rather than inline callbacks.

### Fixes

- **Changing a local account's roles always failed with a 500.** The code path
  that replaced an account's role list handed the database layer an immutable
  list; saving then tried to clear it in place and threw. This was
  deterministic — every attempt to change a local account's roles failed,
  silently, since local account management shipped, because nothing exercised
  the endpoint through its real save path before.

### Known Issues

- A chain stage has no independent timeout or escalation — a stuck stage is
  covered only by the whole-request expiry auto-rule, same as an unstaged
  request.
- A chain stage can require a role or an Access group, not one specific named
  individual.

---

# What's New in Access Approval Tool v1.19.12

### Key Capabilities in this release

None. Documentation accuracy only.

### Fixes

- **The in-app Help now says how to configure the callout credentials so they
  actually take effect.** The previous text named the two variables and said
  they were required, but omitted the step that matters most — pressing **Save**
  in the Access approvals settings afterwards, even when nothing on that screen
  changed. Access decides whether an endpoint requires authentication by probing
  it, and only re-decides when those settings are saved; following the old
  instructions exactly reproduced the failure the 1.19.5–1.19.11 series spent
  its length diagnosing.
- Help also now records the two Access behaviours that make the log readable:
  credentials are never sent up front, so a single unauthenticated attempt is
  the normal first half of the exchange rather than a fault; and each callout is
  delivered from more than one node, so the same request arriving twice is
  expected and the second copy is discarded.

### Known Issues

- None outstanding for this release.

---

# What's New in Access Approval Tool v1.19.11

### Key Capabilities in this release

None. The last correction in a sequence that made callout authentication
actually work.

### Fixes

- **A challenge was being logged as a failure.** Omnissa Access does not send
  credentials up front. Every callout begins with an unauthenticated attempt,
  collects the `401`, and is retried with credentials — often from a different
  address, because Access delivers from several nodes. The bare first attempt is
  half of a working handshake, and it was being reported as *"its approvals
  settings have no credentials saved"*: false on a correctly configured tenant,
  emitted on every callout, and forwarded to syslog. It was also the direct cause
  of hours spent investigating the reverse proxy, HTTP Digest and console field
  limits, none of which were ever the problem.

  The warning is now earned rather than assumed — credentials presented and
  wrong, nothing having ever authenticated, or challenges going unanswered
  repeatedly. A misconfigured install still warns on its very first request.

### Known Issues

- None outstanding for this release.

---

# What's New in Access Approval Tool v1.19.10

### Key Capabilities in this release

None.

### Fixes

- **A duplicate callout took the whole request out.** Access delivers each
  callout from more than one node: two `POST`s carrying the same request id
  arrived 25ms apart from different addresses and both were stored. The lookup
  by request id expected a single row, so two rows made it throw — and all
  sixteen places that use it began returning errors. The request could not be
  opened, decided, swept or pulled, and the queue showed two rows that both said
  *"Request not found"*.

  Ingest is now idempotent: a callout whose request id is already stored is
  acknowledged and discarded. Lookups also tolerate duplicates already in the
  database, so an install that already had one recovers on upgrade rather than
  needing the database edited by hand.

  **This defect was created by fixing another one.** While callout
  authentication was broken, one delivery leg was always rejected, so only one
  copy ever reached the database — a failing handshake was accidentally
  de-duplicating the queue. Making authentication work removed the accident and
  exposed the real defect underneath.

### Known Issues

- Rows duplicated *before* this release cannot be decided individually, because
  every path resolves by request id and reaches the earliest row. Delete the
  extra row instead; the decision on the remaining one is what Access receives.

---

# What's New in Access Approval Tool v1.19.9

### Key Capabilities in this release

- **Callout authentication works.** This is the release where Basic
  authentication on the inbound endpoint actually engages.

### Fixes

- **The `OPTIONS` exemption was why authentication never engaged.** Access
  decides whether an endpoint needs credentials by probing it with `OPTIONS`,
  and only re-decides when its approvals settings are saved. That probe was
  exempt from authentication so the settings could be saved at all — which told
  Access no credentials were needed, and every callout then arrived bare while
  the tenant held a perfectly good username and password.

  Observed rather than reasoned about: with the probe exempt, callouts arrived
  unauthenticated indefinitely, with correct credentials on both sides and every
  network hop proven to carry them. Challenging the probe and re-saving the
  settings produced an authenticated probe within seconds and an accepted
  callout immediately after. The exemption was never necessary — Access answers
  the challenge.

### Known Issues

- **After setting the credentials you must press Save in the Access approvals
  settings.** That save is what makes Access re-probe and learn they are
  required. Without it, Access keeps posting unauthenticated whatever either
  side is configured with.

---

# What's New in Access Approval Tool v1.19.8

### Key Capabilities in this release

None. A diagnostic step on the way to 1.19.9.

### Fixes

- Added a switch to challenge the `OPTIONS` probe rather than exempt it, to test
  whether the exemption was preventing authentication. It was. The switch became
  the default in 1.19.9.

### Known Issues

- Superseded by 1.19.9, where challenging the probe is the shipped behaviour.

---

# What's New in Access Approval Tool v1.19.7

### Key Capabilities in this release

None.

### Fixes

- **A rejected callout now lists what the caller actually sent** — every header
  name, any query parameter names, the content type, and the length of anything
  credential-shaped. Names and lengths only; no value is ever logged, because
  these lines ship to syslog.

  The previous message answered *"were the Basic credentials correct"*, which
  assumes the caller uses the `Authorization` header at all. Access holds a
  username and password for this callout and was sending neither there, so
  "sends its credentials under another name" and "sends no credentials" looked
  identical — and the log stated the second as established fact.

### Known Issues

- None outstanding for this release.

---

# What's New in Access Approval Tool v1.19.6

### Key Capabilities in this release

None.

### Fixes

- **Four places still described callout authentication as optional**, after
  1.19.5 made it mandatory on a tenant-configured install. The worst was the
  **in-app Help page**, which told operators the Username and Password fields in
  the Access console were only needed if Basic authentication was enabled —
  advice that contradicted an application refusing to start without them.
- The Help page also now records that **Access does not verify the credentials
  when you save them**, so a mismatch is not caught at the console. It surfaces
  later as callouts rejected and requests never reaching the queue.

### Known Issues

- None outstanding for this release.

---

# What's New in Access Approval Tool v1.19.5

### Key Capabilities in this release

None. Three corrective changes to the security of the one endpoint that faces
the internet.

### Fixes

- **The address used for rate limiting could be chosen by the caller.** Rate
  limits and the login throttle keyed on the *first* `X-Forwarded-For` entry.
  Proxies **append** to that header, so the leftmost value is written by whoever
  sent the request — varying it produced a fresh bucket each time. That bypassed
  the callout rate limit, and it bypassed the brute-force throttle protecting the
  break-glass local admin password, which is the credential that exists for when
  Omnissa Access is unavailable. Addresses are now counted from the right, under
  `OMNISSA_SECURITY_TRUSTED_PROXY_HOPS`.
- **The obvious fix would not have worked.** Falling back to the socket address
  looks correct and is not: `server.forward-headers-strategy=framework` has
  Spring rewrite the remote address from that same first entry, then strip the
  header. The peer is now captured before any filter runs.
- **The callout endpoint accepted anonymous requests by default.** Basic
  authentication existed but was blank out of the box, so the configuration
  everyone ran was the open one. A request placed by anyone who found the URL is
  indistinguishable from a real one in the queue, and approving it grants a real
  entitlement.

### Known Issues

- On a tenant-configured install this release **will not start** without either
  `OMNISSA_API_USERNAME` / `OMNISSA_API_PASSWORD` or
  `OMNISSA_API_ALLOW_UNAUTHENTICATED=true`. Set one before upgrading. The
  credentials must also be entered in the Access console under
  **Settings → Approvals**, or callouts are rejected and requests stop arriving.
- Behind a reverse proxy with the default `OMNISSA_SECURITY_TRUSTED_PROXY_HOPS=0`, every
  request keys to the proxy's own address, so rate limits and login throttling
  are shared rather than per-caller. Set the hop count to restore per-caller
  behaviour; the first forwarded request is logged with the chain to count.

---

# What's New in Access Approval Tool v1.19.4

### Key Capabilities in this release

None. A single corrective change.

### Fixes

- **SMTP operations are now bounded.** Mail is sent synchronously and no
  timeouts were configured; Jakarta Mail defaults connect, read and write to
  **infinite**. A relay that silently drops packets rather than refusing the
  connection — what a firewalled port 25 does — held the sending thread until
  the process restarted. All three are now 10 seconds.

  The affected thread belongs to the request that made a decision, so the
  visible damage was one stuck response. It is worth fixing now because every
  scheduled job shares a single thread: the same hang reached from a background
  sweep would also stop time-bound access from expiring, silently, while every
  health check stayed green.

### Known Issues

- None outstanding for this release.
- The limitations listed under **Current Known Limitations** below apply.

---

# What's New in Access Approval Tool v1.19.3

### Key Capabilities in this release

- **Expiry rules can be scoped.** They accept the same optional application-name
  pattern and group as a match rule, so *"reject stale Finance requests after
  3 days"* is now expressible. Leaving both blank — the usual case — still
  expires every stale request.

### Fixes

- **Expiry rules ignored their own criteria.** The sweep selected requests by
  age alone, so a rule naming an application rejected *every* pending request
  past its age, whatever the application. The criteria are now applied, and the
  rule form finally exposes the fields — it previously discarded them, which is
  why the problem went unnoticed.

  The obvious correction would have been worse than the fault: the arrival-path
  matcher treats a rule with no criteria as matching *nothing*, and that is the
  ordinary expiry rule. Reusing it would have stopped auto-rejection everywhere
  while every rule stayed enabled and every health check stayed green.

### Known Issues

- None outstanding for this release.
- The limitations listed under **Current Known Limitations** below apply.

> **⚠ Upgrade note — check any expiry rule that names an application or group.**
> Such a rule was rejecting far more than it said. It now rejects only what it
> names, so requests it was previously clearing will begin to accumulate. On the
> first sweep after upgrade each affected rule is logged at WARN. Rules with no
> criteria — the usual case — are unaffected.

---

# What's New in Access Approval Tool v1.19.2

### Key Capabilities in this release

None. Both changes remove a class of failure rather than add behaviour.

### Fixes

- **The SPA is served as a fallback rather than from a list of routes.** Client
  routes were declared twice — once in the backend forwarder, once in the
  router — so adding a page to one and not the other produced a route that
  worked in-app and 404'd on refresh or a direct link. 1.19.1 fixed the
  `/users` instance but left the duplication, which had **already drifted
  again**: the backend forwarded a `/settings/**` that the router does not
  define. The backend now recognises "nothing else claimed this request" rather
  than enumerating pages, so `/api/**`, `/actuator/**`, `/oauth2/**`,
  `/logout`, `/assets/**` and `/error` are excluded by construction instead of
  by list. A path whose last segment contains a `.` still 404s, so a missing
  asset fails loudly rather than returning HTML.

  This mattered beyond refresh: Slack and Teams buttons are deep links of the
  form `/requests/{id}?action=approve`.
- **Paged API responses are declared instead of inferred.** Two endpoints
  returned Spring Data's internal page type directly, making its JSON an
  accidental public contract — one the framework warns about on every response.
  The shape is now an explicit type. **The wire format is unchanged, field for
  field**, captured from the running endpoints before the change and confirmed
  after.
- **A first-run install could not start.** The configuration reference
  documented a blank client-id as the way to disable OAuth sign-in, shipped
  that as the default, and then refused to boot. The claim was unachievable as
  written — a placeholder with an empty default still *defines* the property,
  so the framework saw an invalid registration. The properties are now
  contributed only when a client-id is present, so the documented behaviour and
  the actual behaviour agree. Mail is optional in the same way: a missing SMTP
  host no longer prevents start-up.
- **The reverse-proxy guidance was wrong and has been corrected.** An earlier
  draft of the deployment guide recommended widening the gateway pattern to
  `(/.*)`. That treats the application as the only control. The guide now
  publishes an explicit allow-list, and a build-time check verifies it against
  the routes the application declares.

### Known Issues

- None outstanding for this release. The two startup defects found while
  building it — a blank OAuth client-id preventing start-up despite being the
  documented way to run local-only, and a missing `spring.mail.host` doing the
  same — are **fixed in this release**, listed under Fixes above.
- The limitations listed under **Current Known Limitations** below apply.

> **⚠ Upgrade note — check your reverse proxy.**
> The server no longer enforces a route list, so a default-deny proxy pattern
> is now the only place valid paths are enumerated. Two entries need attention:
> add **`/users(/.*)?`**, which was missing and makes that page 404 on refresh
> or from a direct link, and remove **`/settings(/.*)?`**, which matches a route
> the application does not have. The deployment guide publishes the current
> valid path set, and a build-time check now verifies it against the routes the
> application actually declares, so the pattern cannot silently fall behind.

---

# What's New in Access Approval Tool v1.19.1

### Key Capabilities in this release

This is a corrective release. It adds no new capability; both changes remove
failure modes introduced by the role work in 1.16.1.

### Fixes

- **ERROR-level log noise on an expected condition.** Spring Security
  authorizes *every* dispatch type, not only the original request.
  `/api/approvals/stream` is a Server-Sent Events emitter, so its response is
  committed the moment streaming begins — when the **ASYNC** dispatch was
  re-authorized and denied, Spring Security could not write a 403 into an open
  stream and the wrapped failure reached Tomcat as an `ERROR`. `FORWARD` and
  `ERROR` dispatches were already exempted for exactly this reason; `ASYNC` was
  missed, and only became reachable once role rules replaced
  `anyRequest().authenticated()`. Reproduced and confirmed fixed: an open queue
  tab plus a sign-out elsewhere previously produced a burst of errors at the
  same millisecond, one per open emitter.
- **The Users page returned 404 on refresh or a direct link.** `SpaController`
  forwards a hand-listed set of client routes to the single-page shell, and
  `/users` had been added to the router without being added there. The page
  worked when navigated to in-app and failed on reload — the same shape of
  failure that chat deep links depend on not happening.

### Known Issues

- **The route list was still duplicated** between the backend forwarder and
  the client router; 1.19.1 only made the two cross-reference each other.
  **Resolved in 1.19.2.**
- **Paged API responses were not wrapped in an explicit type**, so pagination
  metadata was inferred by callers rather than declared. **Resolved in
  1.19.2.**
- The limitations listed under **Current Known Limitations** below apply.

---

# What's New in Access Approval Tool v1.19.0

### Key Capabilities in this release

- **Sign-in throttling on the local login form.** Previously the only
  credential-accepting endpoint with no rate limiting of any kind, so the
  break-glass admin password could be guessed at full LAN speed. Three free
  attempts, then a doubling delay capped so a request thread is never held
  long; an address making sustained attempts is refused with HTTP 429. Counters
  expire on their own and clear on success.

  **There is deliberately no account lockout.** Locking an account after N
  failures would let anyone able to reach the login page disable the one
  credential that exists for emergencies — precisely when Omnissa Access is
  unavailable and local sign-in is the only way in. The per-address counter may
  refuse; the **per-username counter only ever delays**, because it is shared
  with the account's real owner and an attacker distributed across many
  addresses could otherwise lock them out. The delay applies to correct
  attempts too, or response timing would reveal a valid password.
- **Configurable password policy** — `OMNISSA_PASSWORD_MIN_LENGTH`,
  `_MIN_DISTINCT`, `_BLOCK_USERNAME`, `_BLOCKLIST_FILE`, and opt-in
  `_REQUIRE_MIXED_CASE` / `_REQUIRE_DIGIT` / `_REQUIRE_SYMBOL`.

  **⚠ Upgrade note:** minimum length is **clamped to a floor of 8** with a
  warning. Configuration may tighten the policy, never remove it — otherwise
  `min-length=1` would silently make the break-glass credential worthless.

### Fixes

- The bundled weak-password list was re-scoped to target **long-but-weak**
  values rather than acting as a general corpus. Of the 10,000 most common
  passwords **only 10 reach twelve characters** — the length rule alone rejects
  the other 9,990 — so bundling a common-password list would add weight while
  implying protection it does not give. The bundled list instead catches
  doubled words, keyboard walks and digit runs, which also defeat composition
  rules (`Passwordpassword1!` satisfies every character-class requirement).
- Composition rules are documented as **not recommended** (NIST SP 800-63B):
  people decorate rather than abandon a weak password, turning `password` into
  `Password1!` — exactly what a cracking toolchain generates first — while
  strong passphrases are rejected. They remain available for a compliance
  requirement.

### Known Issues

- ERROR-level log noise from the SSE stream on sign-out, and the `/users` 404
  on reload. **Both resolved in 1.19.1.**
- If `OMNISSA_PASSWORD_MIN_LENGTH` is lowered, the bundled blocklist is no
  longer sufficient on its own — point `OMNISSA_PASSWORD_BLOCKLIST_FILE` at a
  real wordlist, at which point the full corpus is back in scope.

---

# What's New in Access Approval Tool v1.18.0

*Operability: knowing when something is wrong, and being able to get back in.*

### Key Capabilities in this release

- **Dependency health API** for external monitoring, separating *"this
  container is down"* from *"something it depends on is unhealthy"*.
  `GET /api/health/deps` is public and returns an aggregate word only — no
  tenant name, no error strings, no counts. `GET /api/health/dependencies` is
  authenticated and returns per-component detail. Checks Omnissa Access
  reachability, scheduler liveness, approval drift and webhook delivery.

  **⚠ Upgrade note:** `/actuator/health` is unchanged and remains **liveness
  only**. Docker, `deploy.sh`, CasaOS and the UAG all consume it, and CasaOS
  *recreates the container* when it fails — so a third-party outage must never
  reach it. Point dependency monitoring at the new endpoints, not at
  `/actuator/health`.
- **Scheduler liveness check** — the one failure with no other symptom. Every
  scheduled job shares a single-threaded scheduler, so if the JIT sweeps wedge,
  time-bound access silently never expires while the container, the UI and
  every other check stay green.
- **Approval drift detection** — requests Omnissa Access is holding that the
  queue has no pending record of. Those requesters wait indefinitely and the
  app never provisions, with nothing to indicate why. The queue shows a banner,
  and **Pull from Access** recovers them.
- **Local account management** — add, reset password, enable/disable, change
  roles and delete, all audited, plus **self-service password change** in the
  top bar for any locally signed-in account.
- **Password policy** — twelve characters minimum, rejecting repeated
  characters, well-known passwords, plain sequences, and anything containing
  the username.
- **The last enabled local administrator cannot be disabled, deleted or
  demoted.** An Access user holding Admin through a group does not satisfy the
  guard — the situations break-glass exists for are exactly those where Access
  sign-in is unavailable.

### Fixes

- **`OmnissaRestClient` had no connect or read timeout**, so a hung tenant
  pinned the calling thread indefinitely. Tolerable at one call per dashboard
  load; fatal once polled every minute, where hung probes accumulate until the
  pool starves — the monitoring would have caused the outage it exists to
  detect. Now 5s/5s.
- **Deleting a pending request is refused** (HTTP 409). Omnissa Access holds an
  approval open until it receives a decision, so deleting the local record left
  the requester waiting permanently on a decision that could never be given.
  Five requests sat stuck this way and presented as an Access provisioning
  fault. Decline it first; deleting a decided record is unchanged.
- **Account creation accepted a 4-character password** while password *change*
  required twelve — so a weak password could be set at creation and then not be
  replaceable with an equally weak one.
- The connectivity probe is now shared between the dashboard tile and health,
  rather than each polling the tenant independently.
- `troubleshooting.md` corrected: *"Health endpoint shows DOWN"* claimed
  `/actuator/health` aggregates component health including mail; it is
  liveness-only and mail health has long been disabled.

### Known Issues

- **`OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` cannot rotate a password.** The
  bootstrap runs only when the user table is empty, so changing it on an
  existing install does nothing, silently. Use **Reset password** on the Users
  page. Documented in this release; behaviour unchanged by design.
- **The local login form had no rate limiting**, so the break-glass password
  could be guessed at full speed. **Resolved in 1.19.0.**
- A healthy `notifications` status means the endpoint **accepted** the request,
  not that the message arrived — Power Automate returns `202 Accepted`.

---

# What's New in Access Approval Tool v1.16.1

*Access governance: the tool now decides **who may act**, not just what happens.*

### Key Capabilities in this release

- **Role-based access control**, resolved from **Omnissa Access group
  membership** — `ADMIN`, `APPROVER`, `VIEWER`, `AUDITOR`. Configure with
  `OMNISSA_ROLE_MAP` as `<groupId>:<ROLE>` pairs; `GET /api/auth/claims` shows
  your tenant's group ids paired with their names.

  Matched on **ids, not names**, so renaming a group in Access cannot silently
  drop everyone to Viewer. Viewer is a **fallback, not a floor**: with no map
  configured every user is a Viewer, so enabling the map is the deliberate act
  that grants privilege.

  **⚠ Upgrade note:** requires the **`group`** scope on the OIDC client.
  Without it Access emits no group claim at all and every user silently becomes
  a Viewer.
- **Audit-trail CSV export.** The audit tab's export previously downloaded the
  *request* table — a different dataset — so the trail itself had no export at
  all. Bulk export is gated separately from reading: a Viewer may read the
  record on screen but not take a copy of it.
- **The requester is recorded on every audit event.** The trail stored who
  *acted* but never who the access was *for*; combined with **Delete request**,
  that made the subject of an entry unrecoverable. 176 historical events were
  backfilled, and the count that could not be recovered is logged rather than
  left blank.
- **Decisions state their consequence** in Slack and Teams — *permanent
  access*, *5 minutes, then requestable again*, *1 hour, then gone for good*,
  *temporary: the user may request again*, *permanent: the user is blocked from
  re-requesting*.
- **Warning when Auditor is combined with another role.** Auditor is the only
  restrictive role, so pairing it with any other silently defeats it; alongside
  Admin or Approver it is also a separation-of-duties conflict. Reported at
  sign-in rather than corrected.
- **Slack approvals became deep links**, matching Teams.

  **⚠ Upgrade note:** this removes `POST /api/slack/interactions` (an
  internet-facing unauthenticated endpoint), its signing secret and replay
  window, and `SLACK_APPROVER_MAP`. `SLACK_ACTIONABLE` now requires
  `APP_BASE_URL`. Setup drops from six steps to four.

  Decisions previously happened inside an interaction callback where no
  signed-in user exists — a Slack signature proves the *workspace*, not the
  *person* — so authority came from a second source of truth that failed
  **open**: revoking someone in Access left their Slack buttons working.

### Fixes

- **Teams never received decision, expiry, revoke or reopen notifications.**
  Those payloads used Slack's bare `{"text": …}`, which the retired Office 365
  connector accepted but a Power Automate workflow silently drops. Only the
  new-request card worked, because it alone was built as a card. Two tests were
  asserting the broken shape.
- **Chat deep links lost their decision and their destination.** The review
  dialog opened with nothing selected — the action parameter was cleared in the
  same render that opened it — and signing in discarded the saved destination,
  landing approvers on the dashboard. Approve and Reject were therefore no
  different from Open request, on both platforms.
- **The Auditor role granted nothing.** Viewer was added as a floor, so an
  auditor was also a viewer — and since Viewer already includes reading the
  audit trail, the role was a guaranteed no-op.
- **`GET /api/users` returned bcrypt password hashes**, and **`POST /api/users`
  accepted an `authorities` array**, letting any authenticated caller mint
  themselves an admin.
- **Delete request** was rendered for every role but is admin-only, so an
  approver clicking it received a 403.
- `/api/**` now returns a **JSON 401** instead of redirecting to the login
  page, and **403** returns JSON instead of framework HTML. An expired session
  previously looked like a successful empty response to the client.

### Known Issues

- **Role changes apply only at next sign-in.** Roles come from the token, which
  is a snapshot. By design; documented.
- **Channel membership is not authorization.** A chat message is posted to a
  channel, so every member of it can read the request details regardless of
  role, or of whether they have an account at all. Roles govern who may *act*,
  never who may *see*.
- ERROR-level log noise on the SSE stream, introduced here by replacing
  `anyRequest().authenticated()` with role rules. **Resolved in 1.19.1.**
- The local login form had no rate limiting. **Resolved in 1.19.0.**
- `OmnissaRestClient` had no timeouts. **Resolved in 1.18.0.**

---

# What's New in Access Approval Tool v1.9.5

### Key Capabilities in this release

- **`WEBUI_SCHEME` / `WEBUI_HOSTNAME` / `WEBUI_PORT`** in the ZimaCube compose
  environment point the CasaOS tile at the public URL instead of
  `http://<nas>:8081`.

  **⚠ Upgrade note:** CasaOS builds the link as `scheme://hostname:port_map/`
  and **always appends the port**, so all three must be set together. Worth
  setting: admin OAuth2 login only works on the registered redirect URI, and a
  plain-HTTP link is blocked as mixed content from an HTTPS dashboard. Left
  unset, behaviour is unchanged.
- In-app Help gained condensed **six-step Slack** and **four-step Teams** setup
  walkthroughs, so chat approvals can be configured without leaving the app.

### Fixes

- **Clicking the app's tile in ZimaOS recreated the container instead of
  opening it.** Before opening an app, CasaOS probes it at
  `<scheme>://<hostname>:<port_map>/`; with the port published only on the LAN
  address, that probe resolved to `http://127.0.0.1:8081/` and was refused, so
  CasaOS concluded the app was down and fell into its start/repair path — a
  silent pull and re-create. Port 8081 is now published on loopback as well;
  network exposure and the LAN-only firewall rule are unchanged.
- `deploy.sh` wrote the compose environment file with a truncating redirect, so
  every run discarded the image-tag pin and the new tile settings — silently
  unpinning the image and reverting the CasaOS tile.
- **Corrected the CasaOS update guidance, which was wrong in both directions.**

### Known Issues

- **CasaOS "Check and then update" does not work for this container, and no
  image tag changes that.** ZimaOS resolves *"is an update available?"* by
  looking the app up in a CasaOS **AppStore**; an externally-managed Compose
  app is never found there, so the check reports "latest version" without ever
  contacting the registry. Verified against a live NAS with the container
  pinned to an older digest than the registry tag. Use `compose pull`, or the
  opt-in Watchtower profile. **Open — this is a CasaOS behaviour, not a defect
  in the tool.**
- Teams received no decision, expiry, revoke or reopen notifications.
  **Resolved in 1.16.1.**

---

# What's New in Access Approval Tool v1.9.1

### Key Capabilities in this release

None — corrective release.

### Fixes

- **Webhook posts were rejected with 401, so Teams notifications silently never
  arrived.** The HTTP client was given the URL as a string, which it treats as
  a URI *template* and re-encodes. A Power Automate workflow URL carries an
  already-encoded, signature-protected query, so `%2F` became `%252F` and the
  signature no longer matched. The URL is now passed as a URI. Applied to the
  Slack response post as well.

### Known Issues

- Teams received only the new-request card; decision, expiry, revoke and reopen
  notifications were still dropped for a different reason (payload shape).
  **Resolved in 1.16.1.**

---

# What's New in Access Approval Tool v1.9.0

### Key Capabilities in this release

- **Actionable Microsoft Teams approvals** (`TEAMS_ACTIONABLE`) — new requests
  post an **Adaptive Card** through a Power Automate workflow, with buttons
  that open the request in the tool with the decision pre-selected.

  Buttons are deep links rather than callbacks: Office 365 connectors (which
  supported inline HTTP actions) are retired, and a Power Automate callback
  requires the **premium** HTTP connector. This also means no inbound endpoint,
  no shared secret, and approvers authenticate with the tool's own OIDC login.

  **⚠ Upgrade note:** requires `APP_BASE_URL`.

### Fixes

None recorded.

### Known Issues

- Webhook posts to Power Automate were rejected with 401 because of URL
  re-encoding. **Resolved in 1.9.1.**
- Only the new-request card reached Teams; all lifecycle notifications were
  dropped. **Resolved in 1.16.1.**
- Deep-link buttons did not carry their decision or destination through
  sign-in. **Resolved in 1.16.1.**

---

# What's New in Access Approval Tool v1.8.0

### Key Capabilities in this release

- **Revoke an active grant on demand**, without waiting for a TTL: *Revoke
  access* (the app returns to a requestable state after a short hold) or
  *Revoke and block* (the user stays excluded until an administrator lifts it).
  Both confirmation-gated.
- **Allow re-request** now also covers revoked-and-blocked requests, so a
  permanent revoke has a recovery path in the interface.

### Fixes

- The user's **Deployment Type** is now captured at grant time and written back
  on restore. A restore previously always forced *User-Activated*, silently
  converting an *Automatic* assignment.

### Known Issues

None recorded.

---

# What's New in Access Approval Tool v1.7.2

### Key Capabilities in this release

None — corrective release.

### Fixes

- The Slack block button rendered as `Reject &amp; Block`, because Slack
  HTML-escapes the ampersand.
- The confirmation dialog showed literal asterisks, because a Slack confirm
  object does not render markup.

### Known Issues

None recorded.

---

# What's New in Access Approval Tool v1.7.1

### Key Capabilities in this release

- Slack **Reject and Block** button — a confirmation-gated permanent decline.

### Fixes

- Clicking a Slack button after the request had already been decided reported
  *"Omnissa Access is unreachable"*. The card is simply stale; it now says so
  plainly.

### Known Issues

- Slack button and confirm-dialog text rendered with escaped markup.
  **Resolved in 1.7.2.**

---

# What's New in Access Approval Tool v1.7.0

### Key Capabilities in this release

- **Permanent vs temporary decline.** A temporary decline (the default) rejects
  only that request; a permanent decline excludes the user so the app does not
  reappear. New **Allow re-request** action reverses a block.
- **A permanent decline that cannot be enforced is not recorded as permanent** —
  an `access-block-failed` event is audited instead, so the trail never claims
  an exclusion that Access did not accept.

### Fixes

None recorded.

### Known Issues

None recorded.

---

# What's New in Access Approval Tool v1.6.0

### Key Capabilities in this release

- **JIT lifecycle notifications** — `access.revoked` when a timed grant expires
  and `access.reopened` when the app becomes requestable again, so an approver
  who granted from chat learns that it ended. Toggle with
  `WEBHOOK_NOTIFY_LIFECYCLE`.

### Fixes

None recorded.

### Known Issues

- On Teams, these lifecycle payloads used Slack's message shape and were
  silently dropped. **Resolved in 1.16.1.**

---

# What's New in Access Approval Tool v1.5.7

### Key Capabilities in this release

- **Actionable Slack approvals** (`SLACK_ACTIONABLE`) — an interactive message
  with an access-duration menu and Approve/Reject buttons; the message updates
  in place with the outcome. The inbound endpoint verifies the **Slack
  signature** (HMAC-SHA256, five-minute replay window) before any state change,
  and requires the clicking user to appear in `SLACK_APPROVER_MAP` — a valid
  signature proves the workspace, not that the clicker may approve.
- Decisions from every channel now run through a shared decision service, so a
  chat decision and an interface decision behave identically and carry a
  resolved approver identity.

### Fixes

None recorded.

### Known Issues

- **`SLACK_APPROVER_MAP` is a second source of truth for authority, and it
  fails open.** Revoking someone in Omnissa Access removes their web access
  immediately but leaves their Slack buttons working. **Resolved in 1.16.1** by
  replacing callbacks with deep links, which removed the map, the signing
  secret and the inbound endpoint entirely.

---

# What's New in Access Approval Tool v1.5.0

### Key Capabilities in this release

- **JIT / time-bound access.** Approve for five minutes to thirty days; access
  is automatically revoked at expiry, which genuinely deprovisions the app in
  Omnissa Access. Choose what happens afterwards: **re-requestable** (the app
  returns after a short hold) or **one-time** (it stays gone). Auto-approval
  rules can grant timed access.
- **Revocation works for group-provisioned and directly-assigned apps alike**,
  using a per-user **exclusion** that overrides group access without touching
  the group entitlement.
- **Delete request** — administrator cleanup for stale local records; two-step
  confirmation, fully audited, and never touches Omnissa Access.
- **Backup and restore** for the database and environment file: verified
  archives, retention, a manifest recording the running image digest, and a
  nightly timer.
- **Version-tagged container images** (moving `major.minor` plus the exact
  version).
- **An automated test suite enforced as a CI gate**, so a merge that breaks
  behaviour fails before it reaches an image.

*Shipped as container image `v1.5.6`.*

### Fixes

- Decision delivery reported a false *"Could not reach Omnissa Access"* when
  the decision had in fact been delivered — the unused response body was parsed
  into a strict type and the failure was misclassified.
- SCIM `userName` lookups matched nobody because the filter was double-encoded.
- Notifications named the requester by a numeric id instead of their name or
  email, and chat decisions were audited as `system` rather than the real
  approver.

### Known Issues

- **Deleting a pending request orphaned the live approval in Access.** Access
  holds the approval open until it receives a decision, so the requester waited
  permanently. **Resolved in 1.18.0**, which refuses the deletion.
- A restore always forced *User-Activated*, converting *Automatic* assignments.
  **Resolved in 1.8.0.**

---

# What's New in Access Approval Tool v1.4.0

### Key Capabilities in this release

No functional change. Platform upgrade only.

- Backend: Spring Boot 3.5 → **4.1** (Spring Framework 7, Spring Security 7,
  Jakarta EE 11) and springdoc-openapi 2.8 → 3.0.
- Frontend: React 18 → **19**, Vite 5 → **8**, Tailwind CSS 3 → **4**,
  TypeScript 5 → **6**. Tailwind 4 moved to CSS-first configuration; the custom
  navy palette migrated to a CSS theme block with utility class names
  unchanged. The build's pinned Node version moved to 22 to satisfy Vite 8.

### Fixes

- Security 7 migration: the removed `AntPathRequestMatcher` was replaced with
  `PathPatternRequestMatcher` for the logout and authentication-entry-point
  matchers, and the TLS-redirect connector moved to its relocated factory
  class.

### Known Issues

None recorded. No runtime behaviour, interface behaviour or visual change was
intended or observed.

---

# What's New in Access Approval Tool v1.3.0

### Key Capabilities in this release

- **Pull from Access** on the Awaiting Review tab — manually ingests any
  requests Omnissa Access is holding but never pushed, such as a callout that
  hit a container restart or a transient network gap. Access does not retry on
  its own.

### Fixes

- The custom decision message now reaches the requester's email. The review
  dialog sent the note under the wrong field name, so it was dropped before
  being saved or templated.

### Known Issues

- Drift between Access and the queue had to be noticed by a human before **Pull
  from Access** was any use. Automatic detection arrived in **1.18.0**.

---

# What's New in Access Approval Tool v1.2.1

### Key Capabilities in this release

- Configurable sender address for requester email notifications via
  `SPRING_MAIL_FROM`.

  **⚠ Upgrade note:** previously hardcoded to `no-reply@example.com`, which
  most relays — including Office 365 — reject outright.

### Fixes

None recorded.

### Known Issues

None recorded.

---

# What's New in Access Approval Tool v1.2.0

### Key Capabilities in this release

- **Expired-request handling.** When an administrator or auto-rule decides a
  request that Omnissa Access no longer knows about, the request is no longer
  left stuck in Awaiting Review. It moves to the Deactivated tab with an
  **Expired** badge, a `decision-undeliverable` event is recorded in the audit
  trail, and the webhook emits `request.expired`. The decision endpoint now
  returns a real outcome — delivered, expired or unreachable — and the review
  dialog shows matching notices, with transient outages leaving the request
  pending for retry rather than marking it expired.

### Fixes

None recorded.

### Known Issues

None recorded.

---

# What's New in Access Approval Tool v1.1.1

### Key Capabilities in this release

- **Optional Watchtower auto-update** for the ZimaCube/Docker deployment,
  behind a compose profile. **Disabled by default**; when explicitly enabled it
  checks the registry daily and recreates only the label-scoped approvals
  container. Documented including the Docker-socket security trade-off.

### Fixes

- **`curl` restored in the runtime image.** The base image dropped it after the
  21-JRE release, which silently broke the container healthcheck.

### Known Issues

None recorded.

---

# What's New in Access Approval Tool v1.1.0

### Key Capabilities in this release

- **Decision webhook notifications** — the webhook now fires on every approval
  and rejection, naming the deciding administrator or, for auto-decisions, the
  rule number.
- **Named attribution in audit and syslog decision messages** — decision lines
  now read "Approved by *admin*" / "Rejected by *admin*" with the reviewer's
  note when present, "… (bulk action)" for bulk decisions, and
  "Auto-approved/Auto-rejected by rule #N" for rule decisions.
- **The ZimaCube deployment pulls the published registry image** instead of
  building locally on the NAS.

### Fixes

- **Corrected declined-request documentation.** A declined request is listed as
  Rejected in the tool, the user's Pending state is dropped, and the
  application returns to a locked option in the Access catalog — the user may
  request it again. It was previously documented as deactivating the
  application.

### Known Issues

- The audit trail recorded who *acted* but never who the access was *for*, so
  deleting a request made the subject of its history unrecoverable. **Resolved
  in 1.16.1**, with historical events backfilled.

---

# What's New in Access Approval Tool v1.0.0

*Initial public release.*

### Key Capabilities in this release

- **Approval queue** with live updates via Server-Sent Events — incoming
  requests appear without refreshing.
- **Omnissa Access callout integration** — receives approval callouts, natively
  parses the Access messaging envelope, and posts decisions back through the
  service client API.
- **Administrator sign-in** — local username/password with a first-run
  bootstrap account, and *Sign in with Omnissa Access* via OIDC
  (authorization code with PKCE), plus an optional OAuth-only mode.
- **Consent screen auto-disable** — optional startup call to the Access admin
  API to turn off the user-consent prompt on the OIDC client.
- **Audit trail** recording every incoming request, decision and auto-rule
  action, also written to the application log.
- **Auto-approval rules** — match rules on application-name wildcard and/or
  Access group, and expiry rules that auto-reject requests pending longer than
  N days; the first matching enabled rule wins.
- **Webhook notifications** for new requests in generic, Slack or Teams
  formats.
- **Email notifications** to requesters over SMTP.
- **CSV export** of request history including decision makers.
- **Connectivity status tile** showing whether the service client can obtain a
  tenant token.
- **Log bundle download** from the administrator interface.
- **Syslog export** over UDP, TCP or TLS, including mutual-TLS client
  certificates and private CA bundles.
- **API security** — optional HTTP Basic authentication on the callout endpoint
  and per-IP rate limiting.
- **In-app Help page** documenting setup and the full configuration reference.
- **Deployment options** — Docker Compose with automatic TLS, standalone TLS,
  behind-your-own-reverse-proxy, and a one-script ZimaCube/CasaOS deployment.

### Fixes

Not applicable — initial release.

### Known Issues

- **Every authenticated administrator held full rights.** There were no
  reviewer or read-only roles, so anyone who could sign in could decide, delete
  and reconfigure. **Resolved in 1.16.1.**
- **Approvals were permanent only.** There was no way to grant access for a
  bounded period. **Resolved in 1.5.0.**
- **A decision that Access could not accept left the request stuck** in
  Awaiting Review with no indication why. **Resolved in 1.2.0.**

---

# Current Known Limitations

These apply to v1.19.4 and are design boundaries rather than defects.

- **Single tenant** — one Omnissa Access tenant per deployment.
- **Embedded file database** — right for proof-of-concept scale; no clustering
  and no external-database option.
- **Not a full ITSM system** — multi-stage approval chains shipped in 1.20.0
  and ownership/escalation in 1.21.0, but escalation is a single stage on the
  expiry rule: no second stage, no email escalation, and no per-stage SLA
  inside a chain.
- **Slack and Teams are notification channels, not decision surfaces.** Every
  decision is made in the tool's own interface after sign-in. This is
  deliberate: it is what keeps authority in one place.
- **Channel membership is not authorization.** Everyone in an approvals channel
  can read request details regardless of role. Roles govern who may act, never
  who may see.
- **Teams delivery cannot be confirmed.** Power Automate returns `202
  Accepted`, meaning queued — not delivered.
- **Role changes apply at next sign-in**, because roles come from the token.
- **The Access entitlements API is not guaranteed to be a complete view** of
  what Access uses for authorization. A divergence has been observed once and
  was resolved by recreating the application in Access.
- **CasaOS "Check and then update" cannot detect updates** for an
  externally-managed Compose application. Use `compose pull` or the opt-in
  Watchtower profile.

---

# What's Coming

Planned work, in the order it is likely to land. No commitment to dates.

## Key Capabilities

- **Full configuration from the CasaOS / ZimaCube application settings**, so a
  ZimaCube deployment can be changed without editing an environment file over
  SSH. This means working *with* the CasaOS adopt-and-recreate behaviour rather
  than around it.

## Fixes

- **A short demonstration recording** for the repository landing page.

## Recently shipped from this list

- **Delegation and escalation.** Claim/assign/release plus timed escalation to
  the chat channel and to the approvers themselves, scoped by application and
  group. Shipped in 1.21.0.

- **Multi-stage approvals.** Sequential chains, where a request must clear one
  stage's role or Access group before advancing to the next; rejecting at any
  stage rejects the whole request immediately. Shipped in 1.20.0, ahead of
  delegation/escalation above — the reverse of the original plan, since the
  Access-directory groundwork chains needed (SCIM group-member resolution)
  turned out not to require delegation first.
- **Start with OAuth sign-in disabled, and without `spring.mail.host` set.** The
  configuration reference documented a blank client-id as the way to run
  local-only and that configuration failed to start; the documented path and the
  code now agree. Shipped in 1.19.2.
- **Remove the duplicated route list** between the backend forwarder and the
  client router, and **wrap paged API responses in an explicit type** so
  pagination metadata is declared rather than inferred. Both shipped in 1.19.2.

## Under consideration, not committed

- **An external-database option** is *not* currently planned. It contradicts
  what the tool is for — one tenant, one container, an embedded database,
  deliberately simple — and would make it harder to stand up for exactly the
  audience it targets. If a real need appears, this will be revisited.

---

> ### ⚠️ ONE MORE TIME
>
> **Unsupported. As-is. No warranty. Not an Omnissa product.**
> **Testing and demonstration only — never production, never production data.**
> **Entirely at your own risk.**
