# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.1.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SquidlyMan/Omnissa-Access-Approvals/releases/tag/v1.0.0
