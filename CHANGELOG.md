# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
