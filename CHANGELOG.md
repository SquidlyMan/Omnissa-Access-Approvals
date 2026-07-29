# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.19.3] - 2026-07-28

### Fixed
- **Expiry rules ignored `appPattern` and `groupName` entirely.** `RuleScheduler.runApplyExpiryRules()` selected requests by age alone, so a rule reading *"auto-reject Finance\* pending over 3 days"* rejected **every** pending request past three days, whatever the application or group. An unadvertised auto-reject, acting on real entitlement decisions, on an hourly schedule. The sweep now applies the rule's criteria.

  **The obvious fix would have been worse than the bug.** Reusing the arrival-path matcher looked right and is wrong: `RuleEngine` returns *no match* for a rule carrying neither criterion, and a rule carrying neither criterion is the ordinary expiry rule — the form creates it and `RulesController.validate` explicitly permits it. Every such rule would have silently stopped rejecting anything while sitting enabled and green, and requesters would have waited indefinitely on approvals Access holds open until it receives a decision. 1.18.0 already recorded that failure shape: *"five requests sat stuck this way and presented as an Access provisioning fault."*

  So the two paths now state opposite meanings for an empty rule, deliberately and in their javadoc: `matchesMatchRule` treats it as matching **nothing** (an unfinished rule must not auto-decide everything on arrival), `matchesExpiryRule` as matching **everything** (that is what a criteria-less expiry rule means). Both readings are tested, and each was verified to fail against the opposite implementation rather than merely to pass against this one.

### Changed
- **The expiry rule form gained App name pattern and Group name fields.** They existed on the entity but `RulesPage` hard-coded both to `null` on every expiry rule it created, so the scoping this release fixes was unreachable from the interface. The rules list now states the scope too — a scoped rule rejects far less than an unscoped one, and the rule row is the only place that is visible.
- **On first sweep after upgrade, any expiry rule carrying criteria is logged at WARN**, naming the rule and what it now matches. Correcting this narrows what a rule rejects, and that direction is silent: requests that were being auto-cleared simply start accumulating, with the rule still enabled and every health check green. A changelog entry is documentation; this is detection.

### Tests
- 259 → 267.

## [1.19.2] - 2026-07-27

### Changed
- **The SPA is served as a fallback rather than from a list of routes.** `SpaController` hand-listed client routes and `App.tsx` declared the same routes independently; adding a page to one and not the other produced a route that worked in-app and 404'd on refresh or a direct link. 1.19.1 fixed the `/users` instance of this but only made the two files cross-reference each other in comments, so the duplication survived — and had already drifted again in the other direction, with `SpaController` forwarding a `/settings/**` that has no route in the router. This matters beyond refresh: Slack and Teams buttons are deep links of the form `/requests/{id}?action=approve`.

  Implemented as a **fallback, not a catch-all**. A catch-all `@RequestMapping` runs at order 0, ahead of static resource handling, so it would swallow `/assets/**` and `/favicon.ico` and require a second enumeration to exclude them — trading one list for another. `SpaController` instead handles `NoResourceFoundException`, thrown by the lowest-precedence mapping: reaching it *means* every controller, the actuator and the resource handler already declined, so `/api/**`, `/actuator/**`, `/oauth2/**`, `/logout`, `/assets/**` and `/error` are excluded by construction rather than by list. Backend prefixes keep their real 404, and a path whose last segment contains a `.` is treated as a file so a missing bundle fails loudly instead of returning HTML. `App.tsx` gains a `path="*"` route, since mistyped URLs now reach the client.

  **Deployment note:** the server no longer enforces a route list, which makes a default-deny reverse-proxy pattern the only place valid paths are enumerated. `docs/deployment.md` publishes the current valid path set — **add `/users(/.*)?`, which was missing, and remove `/settings(/.*)?`, which matches no route.** The proxy pattern is a security control: it decides what reaches an internal system at all, and it is the only control still standing if the container is misconfigured or a later release exposes something unnoticed. An earlier draft of this guidance recommended widening the pattern to `(/.*)` to avoid maintenance; that was wrong, because it delegates the decision entirely to the application and assumes the application is correct. The maintenance problem it was trying to solve is instead answered by verification: `ProxyPatternCoverageTest` checks the published pattern against the routes the application declares and fails the build when they diverge.
- **Paged API responses are declared instead of inferred.** `ApprovalController` and `AuditController` returned Spring Data's `Page<T>` straight out of a `@RestController`, so the wire format was whatever `PageImpl` happened to serialize to — an implementation detail acting as a public contract, and one Spring Boot warns about at runtime on every response (`Serializing PageImpl instances as-is is not supported`). A new generic `PagedResponse<T>` record carries the same fields; **the wire format is unchanged, field for field**, captured from the running controllers before the change and re-confirmed after. `spring.data.web.pageable.serialization-mode=VIA_DTO` was rejected as the fix because it nests the counts under a `page` object, moving `totalElements` and `totalPages` and breaking the queue's paging controls. On the frontend, `types.ts` declared the paged shape twice; `Page<T>` is now the single shared generic.

### Fixed
- **A first-run install could not start.** `application.properties` documented "set client-id and client-secret to empty strings to disable OAuth2 login", shipped exactly that as the default, and then failed to boot with `Client id of registration 'omnissa' must not be empty`. The claim was unachievable as written: a placeholder with an empty default *defines* the property key, so Boot saw a registration and rejected it as invalid. The keys have to be **absent**, which a properties file cannot express conditionally. An `EnvironmentPostProcessor` now contributes the same registration properties, with the same defaults, only when a client-id is present and a provider is resolvable — Boot's own mapping, including issuer discovery and the manual-provider fallback, is untouched, and an explicitly-set value still overrides the generated one. The claim is now honoured rather than deleted.

  `SecurityConfig` keys off the presence of the `ClientRegistrationRepository` bean rather than a property, so `oauth2Login` is wired only when a registration exists and the long-standing comment — "only wired when an admin OAuth2 client-id is configured" — is finally true. `/api/config/auth` asks the same bean, so it can no longer advertise a sign-in button the filter chain will not service.

  Half-configuration (a client-id with no issuer and no manual endpoints) previously died with `issuer cannot be empty`. It now starts on local sign-in and logs an ERROR naming the property to set: failing to start is not recoverable, running with one sign-in method is.
- **Mail is genuinely optional.** `MailNotification` injected `JavaMailSender` as a required dependency, so an install without `spring.mail.host` failed to start — despite mail health being disabled by default. It is now resolved per call, and a decision that would have sent mail logs a warning naming the property instead of throwing.

### Tests
- 220 → 255. The routing test requests a route that exists nowhere, and was confirmed to fail against the old controller rather than pass vacuously; companion tests prove `/api/**` and `/actuator/health` are still not forwarded to the SPA. The paged tests assert the serialized JSON — exact key set at every nesting level, every value for a known page, and the empty-result case — plus a classpath scan that fails if any `@RestController` returns `Page` or `Slice` again. A first-run test boots the whole context with neither OAuth nor mail configured — the condition nobody exercises — and `ProxyPatternCoverageTest` verifies the reverse-proxy allow-list published in the deployment guide against the routes the application actually declares.

## [1.19.1] - 2026-07-26

### Fixed
- **ERROR-level log noise on an expected condition.** Spring Security authorizes every dispatch type, not just the original request. `/api/approvals/stream` is an `SseEmitter`, so its response is committed as soon as streaming begins — when the **ASYNC** dispatch was re-authorized and denied, Spring Security could not write a 403 into an open stream and the wrapped failure reached Tomcat as an `ERROR`. `FORWARD` and `ERROR` dispatches were already exempted for the same reason ("authorization already happened on the original request"); `ASYNC` was missed, and only became reachable when role rules replaced `anyRequest().authenticated()`. Reproduced and confirmed fixed: an open queue tab plus a sign-out elsewhere previously produced a burst of errors at the same millisecond, one per open emitter.
- **The Users page 404'd on refresh or a direct link.** `SpaController` forwards a hand-listed set of client routes to the SPA shell and `/users` was added to the router without being added there, so the page worked when navigated to in-app but not on reload. Both files now cross-reference each other.

## [1.19.0] - 2026-07-26

### Added
- **Sign-in throttling on the local login form** — previously the only credential-accepting endpoint with no rate limiting of any kind, so the break-glass admin password could be guessed at full LAN speed. Three free attempts, then a doubling delay capped so a request thread is never held long; an address making sustained attempts is refused with HTTP 429. Counters expire on their own and clear on success.

  **Deliberately no account lockout.** Locking an account after N failures would let anyone able to reach the login page disable the one credential that exists for emergencies — precisely when Omnissa Access is unavailable and local sign-in is the only way in. The per-address counter may refuse; the **per-username counter only ever delays**, because it is shared with the account's real owner and an attacker distributed across addresses could otherwise lock them out. The delay applies to correct attempts too, or response timing would reveal a valid password.
- **Configurable password policy** — `OMNISSA_PASSWORD_MIN_LENGTH`, `_MIN_DISTINCT`, `_BLOCK_USERNAME`, `_BLOCKLIST_FILE`, and opt-in `_REQUIRE_MIXED_CASE` / `_REQUIRE_DIGIT` / `_REQUIRE_SYMBOL`. Minimum length is **clamped to a floor of 8** with a warning: configuration may tighten the policy, never remove it, or `min-length=1` would silently make the break-glass credential worthless.

### Changed
- The bundled weak-password list targets **long-but-weak** values rather than a general corpus. Of the 10,000 most common passwords **only 10 reach 12 characters** — the length rule alone rejects the other 9,990 — so bundling a common-password list would add weight while implying protection it does not give. The bundled list instead catches doubled words, keyboard walks and digit runs, which also defeat composition rules (`Passwordpassword1!` satisfies every character-class requirement). Point `OMNISSA_PASSWORD_BLOCKLIST_FILE` at a real wordlist if you lower the minimum length, at which point the whole corpus is back in scope.
- Composition rules are documented as **not recommended** (NIST SP 800-63B): people decorate rather than abandon a weak password, turning `password` into `Password1!` — exactly what a cracking toolchain generates first — while strong passphrases such as `correct horse battery staple` are rejected. Provided for compliance requirements.

## [1.18.0] - 2026-07-26

Operability: knowing when something is wrong, and being able to get back in.

### Added
- **Dependency health API** for external monitoring, separating *"this container is down"* from *"something it depends on is unhealthy"*. `/actuator/health` is unchanged and remains **liveness only** — Docker, `deploy.sh`, CasaOS and the UAG all consume it, and CasaOS *recreates the container* when it fails, so a third-party outage must never reach it. New `GET /api/health/deps` (public, aggregate word only — no tenant name, no error strings, no counts) and `GET /api/health/dependencies` (authenticated, per-component detail). Checks Omnissa Access reachability, scheduler liveness, approval drift and webhook delivery. See [Monitoring](docs/monitoring.md).
- **Scheduler liveness check** — the one failure with no other symptom. Every `@Scheduled` job shares Spring's single-threaded scheduler, so if the JIT sweeps wedge, time-bound access silently never expires while the container, the UI and every other check stay green.
- **Approval drift detection** — requests Omnissa Access is holding that the queue has no pending record of. Those requesters wait indefinitely and the app never provisions, with nothing to indicate why. The queue shows a banner and **Pull from Access** recovers them.
- **Local account management** — add, reset password, enable/disable, change roles and delete, all audited, plus **self-service password change** in the top bar for any locally signed-in account. Local sign-in is the deliberate break-glass route: roles come from Access group membership, so a local admin is the only way in when the tenant is unreachable or the role map is wrong.
- **Password policy** — 12 characters minimum, rejecting repeated characters, well-known passwords, plain sequences, and anything containing the username. Deliberately **no uppercase/digit/symbol requirement**: composition rules push people towards `Password1!` while adding little entropy and rejecting strong passphrases.

### Fixed
- **`OmnissaRestClient` had no connect or read timeout**, so a hung tenant pinned the calling thread indefinitely. Tolerable at one call per dashboard load; fatal once polled every minute, where hung probes accumulate until the pool starves — the monitoring would have caused the outage it exists to detect. Now 5s/5s.
- **Deleting a pending request is refused** (HTTP 409). Omnissa Access holds an approval open until it receives a decision, so deleting the local record left the requester waiting permanently on a decision that could never be given — five requests sat stuck this way and presented as an Access provisioning fault. Decline it first; deleting a decided record is unchanged.
- **Account creation accepted a 4-character password** while password *change* required 12 — so a weak password could be set at creation and then not be replaceable with an equally weak one.
- The connectivity probe is now shared between the dashboard tile and health, rather than each polling the tenant independently.

### Security
- The tool **refuses to disable, delete or demote the last enabled local administrator**, explaining why rather than failing bare. An Access user holding Admin through a group does not satisfy the guard — the situations break-glass exists for are exactly those where Access sign-in is unavailable.
- Documented that `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` **cannot rotate a password**: the bootstrap runs only when the user table is empty, so changing it on an existing install does nothing, silently.

### Documentation
- New [Monitoring](docs/monitoring.md) reference: both endpoints and why they are separate, the Uptime Kuma and UAG recipes, and a per-component runbook.
- Recorded that the **UAG health monitor connects directly to the internal resource** and does not traverse the edge service's proxy pattern, so `/actuator/health` needs no whitelisting there.
- Recorded that a healthy `notifications` status means the endpoint **accepted** the request, not that the message arrived — Power Automate returns `202 Accepted`, which is exactly how the Teams payload-shape bug hid.
- `troubleshooting.md` corrected: *"Health endpoint shows DOWN"* claimed `/actuator/health` aggregates component health including mail; it is liveness-only and mail health has long been disabled.
- `OMNISSA_ROLE_MAP` and `OMNISSA_ADMIN_OAUTH_SCOPE` added to the configuration reference and env template, and the Access setup guide now registers the **`group`** scope — without it no group claim is emitted and every user silently becomes a Viewer.

## [1.16.1] - 2026-07-26

Access governance: the tool now decides **who may act**, not just what happens.

### Added
- **Role-based access control**, resolved from **Omnissa Access group membership** — `ADMIN`, `APPROVER`, `VIEWER`, `AUDITOR`. Configure with `OMNISSA_ROLE_MAP` as `<groupId>:<ROLE>` pairs; `GET /api/auth/claims` shows your tenant's group ids paired with their names. Matched on **ids, not names**, so renaming a group in Access cannot silently drop everyone to Viewer. Viewer is a **fallback, not a floor**: with no map configured every user is a Viewer, so enabling the map is the deliberate act that grants privilege. Requires the `group` scope, now requested by default.
- **Audit-trail CSV export** (`/api/audit/export.csv`). The audit tab's export previously downloaded the *request* table — a different dataset — so the trail itself had no export at all. Bulk export is gated separately from reading: a Viewer may read the record on screen but not take a copy of it.
- **The requester is recorded on every audit event.** The trail stored who *acted* but never who the access was *for*; combined with **Delete request**, that made the subject of an entry unrecoverable. 176 historical events were backfilled, and the count that could not be recovered is logged rather than left blank.
- **Decisions state their consequence** in Slack and Teams — *permanent access*, *5 minutes, then requestable again*, *1 hour, then gone for good*, *temporary: the user may request again*, *permanent: the user is blocked from re-requesting*. The `generic` format gains `permanent`, `accessTtlMinutes` and `reRequestable` as structured fields.
- **Warning when `ROLE_AUDITOR` is combined with another role.** Auditor is the only restrictive role, so pairing it with any other silently defeats it; alongside Admin or Approver it is also a separation-of-duties conflict. Reported at sign-in rather than corrected.

### Changed
- **Slack approvals are now deep links**, matching Teams. Decisions previously happened inside an interaction callback where no signed-in user exists — a Slack signature proves the *workspace*, not the *person* — so authority came from `SLACK_APPROVER_MAP`, a second source of truth that failed **open**: revoking someone in Access left their Slack buttons working. This removes `POST /api/slack/interactions` (an internet-facing unauthenticated endpoint), its signing secret and replay window, and `SLACK_APPROVER_MAP`. `SLACK_ACTIONABLE` now requires `APP_BASE_URL`. Setup drops from six steps to four.
- `/api/**` returns a **JSON 401** instead of redirecting to the login page, and **403** returns JSON instead of Boot's Whitelabel HTML. An expired session previously looked like a successful empty response to the SPA.
- The SPA hides controls a role cannot use; auditors route to the audit view rather than an empty queue.

### Fixed
- **Teams never received decision, expiry, revoke or reopen notifications.** Those payloads used Slack's bare `{"text": …}`, which the retired Office 365 connector accepted but a Power Automate workflow silently drops. Only the new-request card worked, because it alone was built as a card. Two tests were asserting the broken shape.
- **Chat deep links lost their decision and their destination.** The review dialog opened with nothing selected — the `?action=` parameter was cleared in the same render that opened it — and signing in discarded the saved destination, landing approvers on the dashboard. Approve and Reject were therefore no different from Open request, on both platforms, since #55.
- **The Auditor role granted nothing.** `ROLE_VIEWER` was added as a floor, so an auditor was also a viewer — and since Viewer already includes reading the audit trail, the role was a guaranteed no-op.
- **`GET /api/users` returned bcrypt password hashes**, and **`POST /api/users` accepted an `authorities` array**, letting any authenticated caller mint themselves an admin.
- **Delete request** was rendered for every role but is admin-only, so an approver clicking it got a 403.

### Documentation
- A **Roles** section in the README, `SECURITY.md` and the in-app Help (which had no role documentation at all), including the traps: ids not names, additive matching, changes applying only at next sign-in, and not mapping an existing operational group.
- Both chat guides now state that **channel membership is not authorization** — roles govern who may *act*, never who may *see*.
- The re-request note now separates the two **Deployment Type** paths: *Automatic* re-provisions immediately with no request involved, while *User-Activated* makes the app requestable again and an auto-approval rule acts only on the next request.

## [1.9.5] - 2026-07-25

### Fixed
- **Clicking the app's tile in ZimaOS recreated the container instead of opening it.** Before opening an app, CasaOS probes it at `<scheme>://<hostname>:<port_map>/` (`ComposeApp.HealthCheck`); with the port published only on the LAN IP that probe resolved to `http://127.0.0.1:8081/` and was refused, so CasaOS concluded the app was down and fell into its start/repair path — a silent `pull` + `up -d`. Port 8081 is now published on loopback as well (host-only; network exposure and the `DOCKER-USER` LAN-only rule are unchanged).
- `deploy.sh` wrote the compose `.env` with a `>` redirect, so every run discarded `OMNISSA_IMAGE_TAG` and the new `WEBUI_*` settings — silently unpinning the image tag and reverting the CasaOS tile. `LAN_IP` is now rewritten in place.

### Added
- `WEBUI_SCHEME` / `WEBUI_HOSTNAME` / `WEBUI_PORT` in the ZimaCube compose `.env` point the CasaOS tile at the public URL instead of `http://<nas>:8081`. CasaOS builds the link as `scheme://hostname:port_map/index` and **always appends the port**, so the three must be set together. Worth setting: admin OAuth2 login only works on the registered redirect URI, and a plain-`http` link is blocked as mixed content from an HTTPS dashboard. Left unset, behavior is unchanged.

### Changed
- **Corrected the CasaOS update guidance, which was wrong in both directions.** The *"Check and then update"* button does not work for this container and no image tag changes that: ZimaOS resolves *"is an update available?"* by looking the app up in a CasaOS **AppStore**, and an externally-managed Compose app is never found there, so the check reports "latest version" without ever contacting the registry. Verified against the live NAS with the container pinned to `1.9` while the registry `1.9` tag pointed at a newer digest. `OMNISSA_IMAGE_TAG` is retained and re-scoped as deterministic pinning rather than a CasaOS workaround.
- In-app Help gained condensed **6-step Slack** and **4-step Teams** setup walkthroughs, so chat approvals can be configured without leaving the app.
- Documentation now names the **env file** wherever it tells an admin to set a variable, and the Teams setup steps match the current Power Automate UI (*"Send Webhook Alerts to a Channel"*, *"Copy webhook link"*).

## [1.9.1] - 2026-07-25

### Fixed
- Webhook posts rejected with **401 Unauthorized**, so Teams notifications silently never arrived. `RestTemplate` was given the URL as a `String`, which it treats as a URI *template* and re-encodes — a Power Automate workflow URL carries an already-encoded, signature-protected query (`sp=%2Ftriggers%2Fmanual%2Frun&sig=…`), so `%2F` became `%252F` and the signature no longer matched. The URL is now passed as a `URI`. Applied to the Slack `response_url` post as well.

## [1.9.0] - 2026-07-25

### Added
- **Actionable Microsoft Teams approvals** (`TEAMS_ACTIONABLE`) — new requests post an **Adaptive Card** through a Power Automate workflow, with buttons that open the request in the tool with the decision pre-selected. Buttons are deep links rather than callbacks: Office 365 connectors (which supported `Action.Http`) are retired, and a Power Automate callback requires the **premium** HTTP connector. This also means no inbound endpoint, no shared secret, and approvers authenticate with the tool's own OIDC login. Requires `APP_BASE_URL`. See [docs/teams-approvals.md](docs/teams-approvals.md).

## [1.8.0] - 2026-07-25

### Added
- **Revoke an active grant on demand**, without waiting for a TTL: *Revoke access* (the app returns to a requestable state after a short hold) or *Revoke and block* (the user stays excluded until an admin lifts it). Both confirmation-gated.
- **Allow re-request** now also covers revoked-and-blocked requests, so a permanent revoke has a recovery path in the UI.

### Changed
- The user's **Deployment Type** (`activationPolicy`) is captured at grant time and written back on restore — previously a restore always forced `USER_ACTIVATED`, silently converting an *Automatic* assignment.

## [1.7.2] - 2026-07-24

### Fixed
- Slack block button rendered as `Reject &amp; Block` (Slack HTML-escapes `&`) and the confirm dialog showed literal `*asterisks*` (a confirm object does not render mrkdwn).

## [1.7.1] - 2026-07-24

### Added
- Slack **⛔ Reject and Block** button — a confirmation-gated permanent decline.

### Fixed
- Clicking a Slack button after the request was already decided reported *"Omnissa Access is unreachable"*. The card is simply stale; it now says so plainly.

## [1.7.0] - 2026-07-24

### Added
- **Permanent vs temporary decline.** A temporary decline (default) rejects only that request; a permanent decline excludes the user so the app does not reappear. A permanent decline that cannot be enforced is **not** recorded as permanent (`access-block-failed` is audited instead). New **Allow re-request** action reverses a block.

## [1.6.0] - 2026-07-24

### Added
- **JIT lifecycle notifications** — `access.revoked` when a timed grant expires and `access.reopened` when the app becomes requestable again, so an approver who granted from chat learns that it ended. Toggle with `WEBHOOK_NOTIFY_LIFECYCLE`.

## [1.5.7] - 2026-07-23

### Added
- **Actionable Slack approvals** (`SLACK_ACTIONABLE`) — an interactive message with an access-duration menu and Approve/Reject buttons; the message updates in place with the outcome. The inbound endpoint `POST /api/slack/interactions` verifies the **Slack signature** (HMAC-SHA256, 5-minute replay window) before any state change, and requires the clicking user to appear in `SLACK_APPROVER_MAP` — a valid signature proves the workspace, not that the clicker may approve. See [docs/slack-approvals.md](docs/slack-approvals.md).

### Changed
- Decisions from every channel now run through a shared `DecisionService`, so a chat decision and a UI decision behave identically and carry a resolved approver identity.

## [1.5.0] - 2026-07-23

### Added
- **JIT / time-bound access.** Approve for 5 minutes to 30 days; access is automatically revoked at expiry, which genuinely deprovisions the app in Omnissa Access. Choose what happens afterwards: **re-requestable** (the app returns after a short hold) or **one-time** (it stays gone). Auto-approval rules can grant timed access via `grantTtlMinutes`. See [docs/access-lifecycle.md](docs/access-lifecycle.md).
- Revocation works for **group-provisioned and directly-assigned** apps alike, using a per-user **exclusion** that overrides group access without touching the group entitlement.
- **Delete request** — admin cleanup for stale local records (two-step confirmation, fully audited, never touches Omnissa Access).
- **Backup and restore** for the H2 database and env file: verified archives, retention, a manifest recording the running image digest, and a nightly systemd timer. See [docs/deployment.md](docs/deployment.md#backup-and-restore).
- Version-tagged container images (moving `major.minor` plus the exact version) so the CasaOS update check detects new builds.

### Fixed
- Decision delivery reported a false *"Could not reach Omnissa Access"* when the decision had in fact been delivered — the unused response body was parsed into a strict type and the failure was misclassified.
- SCIM `userName` lookups matched nobody because the filter was double-encoded.
- Notifications named the requester by a numeric id instead of their name/email, and chat decisions were audited as `system` rather than the real approver.

## [1.4.0] - 2026-07-22

### Changed
- Upgraded the backend from Spring Boot 3.5.16 to Spring Boot 4.1.0 (Spring Framework 7, Spring Security 7, Jakarta EE 11) and springdoc-openapi from 2.8.17 to 3.0.3 (the Spring Boot 4 / Framework 7 compatible line). Security 7 migration: replaced the removed `AntPathRequestMatcher` with `PathPatternRequestMatcher` for the logout and authentication-entry-point matchers; the Tomcat SSL-redirect connector now uses the relocated `org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory` and its renamed `addAdditionalConnectors` method. No runtime behavior change.
- Upgraded the frontend major dependencies together: React 18 → 19 (`react`/`react-dom`/`@types/react`/`@types/react-dom`), Vite 5 → 8 with `@vitejs/plugin-react` 4 → 6, Tailwind CSS 3 → 4, and TypeScript 5 → 6. Tailwind 4 moved to CSS-first configuration: the PostCSS setup was replaced with the official `@tailwindcss/vite` plugin, the CSS entry now uses `@import "tailwindcss"`, and the custom `omnissa` navy palette (`#132250` / dark `#0c1636` / light `#e9ecf5`) migrated to a CSS `@theme` block — the `bg-omnissa` / `text-omnissa` / `-dark` / `-light` / `ring` / `border` / `accent` utility classes and the navy banner are unchanged. The `frontend-maven-plugin` Node pin was bumped from v20.14.0 (npm 10.7.0) to v22.23.1 (npm 10.9.8) to satisfy Vite 8's Node requirement. No UI behavior or visual change.

## [1.3.0] - 2026-07-04

### Added
- **Pull from Access** button on the Awaiting Review tab — manually ingests any requests Omnissa Access is holding but never pushed (a callout that hit a container restart or transient network gap; Access does not auto-retry). Endpoint `POST /api/approvals/pull`.

### Fixed
- Custom decision message now reaches the requester's email. The review dialog sent the note under the wrong field name (`responseMessage` vs the API's `message`), so it was dropped before being saved or templated.

## [1.2.1] - 2026-07-04

### Added
- Configurable sender address for requester email notifications via `SPRING_MAIL_FROM` (previously hardcoded to `no-reply@example.com`, which most relays — including Office 365 — reject).

## [1.2.0] - 2026-07-03

### Added

- **Expired-request handling** — when an admin (or auto-rule) decides a
  request that Omnissa Access no longer knows about (the decision PUT is
  answered 4xx), the request is no longer left stuck in Awaiting Review: it
  is automatically moved to the Deactivated tab with an **Expired** badge,
  a `decision-undeliverable` event is recorded in the audit trail, and the
  webhook (if configured) emits `request.expired`. The decision endpoint
  now returns a real outcome (`delivered`, `expired`, or `unreachable`)
  and the review dialog shows matching notices: an amber "moved to
  Deactivated" notice for expired requests and a red "could not reach
  Omnissa Access — try again" error for transient outages (which leave the
  request pending for retry).

## [1.1.1] - 2026-07-03

### Added

- **Optional Watchtower auto-update** for the ZimaCube/Docker deployment —
  a `watchtower` service in `deploy/zimacube/docker-compose.yml` behind the
  `autoupdate` compose profile. **Disabled by default**; when explicitly
  enabled (`docker compose --profile autoupdate up -d`) it checks GHCR
  daily and recreates only the label-scoped approvals container. Documented
  in `docs/deployment.md` and the in-app Help page, including the
  Docker-socket security trade-off and the note that CasaOS "Check and
  then update" does not reliably detect new registry images.

### Fixed

- **`curl` restored in the runtime image** — Temurin dropped `curl` after
  the 21-jre base image, which silently broke the container healthcheck;
  it is now installed explicitly in the runtime stage.

## [1.1.0] - 2026-07-03

### Added

- **Decision webhook notifications** — the webhook now fires on every
  approval/rejection (`request.decided` in `generic` format; attribution
  text in `slack`/`teams` formats), naming the deciding admin or, for
  auto-decisions, the auto-approval rule number.

### Changed

- **Named attribution in audit/syslog decision messages** — decision lines
  in the audit trail, application log, and syslog export now read
  "Approved by \<admin\>" / "Rejected by \<admin\>" (with the reviewer's
  note when present), "… (bulk action)" for bulk decisions, and
  "Auto-approved/Auto-rejected by rule #N" for rule decisions.
- **ZimaCube deployment pulls the published GHCR image**
  (`ghcr.io/squidlyman/omnissa-access-approvals`) instead of building
  locally on the NAS — first-run bootstrap is unchanged, and updates now
  also work via CasaOS "Check and then update".

### Fixed

- **Corrected declined-request documentation** (Help page and docs): a
  declined request is listed as Rejected in the tool, the user's Pending
  state is dropped, and the application returns to a locked option in the
  Access catalog — the user can request it again. (Previously documented
  incorrectly as deactivating the application.)

## [1.0.0] - 2026-07-03

Initial public release.

### Added

- **Approval queue** with live updates via Server-Sent Events (SSE) —
  incoming requests appear in the admin UI without refreshing.
- **Omnissa Access callout integration** — receives approval callouts on
  `POST /api/approvals/new`, natively parses the Omnissa Access messaging
  envelope (including the
  `application/vnd.vmware.horizon.manager.messaging.message+json` content
  type), and posts decisions back through the service client API.
- **Admin login**: local username/password (first-run bootstrap account) and
  "Sign in with Omnissa Access" via OIDC (authorization code + PKCE), with
  optional OAuth-only mode (`OMNISSA_AUTH_LOCAL_LOGIN_DISABLED`).
- **Consent screen auto-disable** — optional startup call to the Omnissa
  Access admin API to turn off the user-consent prompt on the OIDC admin
  login client (`OMNISSA_ADMIN_OAUTH_DISABLE_CONSENT`).
- **Audit trail** recording every incoming request, decision (with the
  deciding admin's identity), and auto-rule action; also written to the
  application log under the `AUDIT` logger.
- **Auto-approval rules** — match rules (app-name wildcard pattern and/or
  Access group) that auto-approve/reject on arrival, and expiry rules that
  auto-reject requests pending longer than N days (checked hourly); first
  matching enabled rule wins.
- **Webhook notifications** for new requests, in `generic`, `slack`, or
  `teams` payload formats; fire-and-forget delivery.
- **Email notifications** to requestors via SMTP.
- **CSV export** of the full request history including decision makers.
- **Connectivity status tile** showing whether the service client can obtain
  a token from the Omnissa Access tenant.
- **Log bundle download** (last hour) from the admin UI.
- **Syslog export** over UDP, TCP, or TLS, including mutual-TLS client
  certificates and private CA bundles (inline PEM or file paths).
- **API security** — optional HTTP Basic auth on the callout endpoint and
  per-IP rate limiting (HTTP 429).
- **In-app Help page** documenting setup and the full configuration
  reference.
- **Deployment options** — Docker Compose with Caddy (automatic TLS),
  standalone TLS, behind-your-own-reverse-proxy mode, and a one-script
  ZimaCube/CasaOS deployment.

[1.4.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/releases/tag/v1.0.0
