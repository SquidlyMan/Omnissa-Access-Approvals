# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.3.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/releases/tag/v1.0.0
