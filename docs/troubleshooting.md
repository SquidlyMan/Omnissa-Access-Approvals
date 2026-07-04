# Troubleshooting

Real failure modes seen in the field, and how to fix them.

## "Unable to connect to the URI" when saving Settings > Approvals

When you save the approvals settings, the Omnissa Access cloud service
probes your URI with an `OPTIONS` request. The save fails unless **all** of
these hold:

- **DNS resolves publicly.** The hostname in the URI must resolve from the
  internet, not just on your LAN (split-horizon DNS with no public record is
  a common cause).
- **TLS is valid.** The certificate must be publicly trusted, unexpired, and
  match the hostname — self-signed certificates are rejected.
- **The endpoint is reachable.** Port 443 must be open from the internet to
  your reverse proxy, and the proxy must forward `/api/approvals/new` to the
  app.
- **The `OPTIONS` probe returns 200.** The tool answers `OPTIONS
  /api/approvals/new` unauthenticated by design (even with API Basic auth
  enabled). Test from outside your network:

  ```bash
  curl -i -X OPTIONS https://<your-host>/api/approvals/new
  ```

## Callouts arrive but the queue stays empty

Omnissa Access wraps approval callouts in a **messaging envelope** rather
than posting the approval payload directly. The tool parses this envelope
natively, so normally requests just appear. If the queue stays empty:

- Check the container logs: `docker logs <container>`. Lines like
  `Ignoring callout probe` mean Access sent a probe/keep-alive message, not
  an actual request — that is normal and expected.
- Confirm the application actually has **License Approval Required** enabled
  and the assignment deployment type triggers a request (see
  [Omnissa Access setup](omnissa-access-setup.md)).
- If API Basic auth is enabled, verify the Username/Password in
  **Settings > Approvals** match `OMNISSA_API_USERNAME` /
  `OMNISSA_API_PASSWORD` — mismatches show as 401s in the proxy/app logs.

## HTTP 415 Unsupported Media Type on the callout endpoint

Omnissa Access posts callouts with the content type
`application/vnd.vmware.horizon.manager.messaging.message+json`. The tool
accepts this natively (alongside regular `application/json`). If you see
415s, make sure your reverse proxy is not rewriting or filtering the
`Content-Type` header, and that you are running a current version of the
tool.

## OIDC admin login fails

- **Issuer URI must be exact**: `https://<tenant>/SAAS/auth` — the `issuer`
  value from the tenant's `/.well-known/openid-configuration`. Anything
  else (`/SAAS/auth/acs`, the bare tenant host, a trailing slash) breaks
  discovery or ID-token validation.
- **Redirect URI must match exactly** — the value registered on the Access
  client and `OMNISSA_ADMIN_OAUTH_REDIRECT_URI` must both be
  `https://<your-host>/login/oauth2/code/omnissa`, using the public
  hostname (not the backend host or port).
- **PKCE**: Access enforces PKCE on the authorization-code grant; the tool
  supports it — no client change needed.
- Behind a reverse proxy, `X-Forwarded-Proto` must reach the app, or the
  generated redirect URI will be `http://` and Access will reject it.

## Consent screen appears on OAuth2 login

The OIDC client has **User Consent Prompt** enabled. Either disable it in
the Access console, or set `OMNISSA_ADMIN_OAUTH_DISABLE_CONSENT=true` and
restart — the tool disables it automatically via the Access admin API
(requires the service client to have admin rights).

## Health endpoint shows DOWN

`/actuator/health` aggregates component health, including mail. A wrong or
unreachable `SPRING_MAIL_HOST` (or wrong port/credentials/STARTTLS setting)
makes the overall status `DOWN` even though the app works. Fix the SMTP
settings or remove them if you don't use email notifications.

## HTTP 429 on the callout endpoint

The per-IP rate limit was hit — the default is 60 requests/minute per source
IP on `POST /api/approvals/new`. Raise `OMNISSA_API_RATE_LIMIT` or set it to
`0` to disable. Note: if all callouts arrive through a proxy that hides the
client IP, they share one bucket — make sure the proxy passes
`X-Forwarded-For`.

## Live queue updates stall behind nginx

The SSE endpoint `/api/approvals/stream` needs `proxy_buffering off`,
`proxy_cache off`, HTTP/1.1, and a long `proxy_read_timeout` — see the
nginx snippet in [deployment](deployment.md).

## Request stuck in Awaiting Review / decision not delivered

Two distinct failure modes when a decision is submitted:

- **Transient Access outage** (network error, HTTP 5xx): the request stays
  in Awaiting Review and the review dialog shows a red "Could not reach
  Omnissa Access — decision not delivered" error. Try again once the tenant
  is reachable; expiry rules also retry on the hourly scheduler.
- **Request unknown to Access** (HTTP 4xx — the request no longer exists on
  the tenant): the request is marked **Expired** automatically. It moves to
  the Deactivated tab with an Expired badge, the audit trail records a
  `decision-undeliverable` event, and the webhook (if configured) emits
  `request.expired`.

## Still stuck?

Download the **Log Bundle** (last hour) from the in-app Help page, or check
`docker logs`. For suspected security issues, see
[SECURITY.md](../SECURITY.md).

## Requests missing from the queue (held pending in Access)

Omnissa Access pushes each callout once and does not retry. If a push lands during a container restart or a transient network gap, Access keeps the request **Pending** on its side but it never reaches the tool, and re-requesting the same app does nothing (Access already considers it pending).

Fix: on the **Awaiting Review** tab, click **Pull from Access**. The tool fetches every pending request Access is holding and ingests any it does not already have. Safe to click anytime — it only adds requests missing locally.
