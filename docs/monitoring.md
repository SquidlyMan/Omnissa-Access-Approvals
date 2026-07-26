# Monitoring

The tool exposes two separate health signals, because *"the container is down"*
and *"something it depends on is unhealthy"* call for different responses — the
first should page someone, the second should not.

> Not an Omnissa product — see [NOTICE.md](../NOTICE.md). Intended for
> testing/demo use only.

## Endpoints

| Endpoint | Auth | Answers |
|---|---|---|
| `GET /actuator/health` | public | **Liveness only.** Is this container running? |
| `GET /api/health/deps` | public | Aggregate dependency status: `UP`, `DEGRADED` or `DOWN`. Nothing else. |
| `GET /api/health/dependencies` | session | Full per-component detail. |

```console
$ curl https://approvals.example.com/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ curl https://approvals.example.com/api/health/deps
{"status":"UP"}
```

### Why liveness ignores dependencies

`/actuator/health` deliberately reports nothing about Omnissa Access, the
scheduler or notifications. It is consumed by the Docker health check,
`deploy.sh`, CasaOS's app-tile probe and the UAG health monitor — and **CasaOS
recreates the container when that probe fails**, while the UAG de-pools the
backend.

If a tenant outage turned that endpoint red, a perfectly healthy service would
be restarted and taken out of rotation because of someone else's problem. So
liveness stays local: no tenant call, no external dependency, typically a
sub-30ms response. That is also what makes it safe to poll aggressively.

The same reasoning already existed in the codebase before this feature —
`management.health.mail.enabled=false` stops an unreachable SMTP server from
failing the Docker health check.

### Why `/api/health/deps` returns 200 even when degraded

An HTTP monitor treats a non-2xx as down. Returning 503 for `DEGRADED` would
make every dependency warning look like an outage, which is the distinction the
endpoint exists to draw. It always returns **200**; the *body* carries the
state, so a **keyword** monitor trips while a plain HTTP monitor stays green.

### Why the public endpoint is so sparse

`/api/health/deps` returns only a status word — **no tenant hostname, no error
strings, no counts**. A drift or pending count would leak request volume to
anyone able to reach the URL, and error strings leak internal detail. Everything
specific lives behind a session at `/api/health/dependencies`.

## What is checked

| Component | `DEGRADED` when | Why it matters |
|---|---|---|
| `omnissaAccess` | The tenant is configured but a `client_credentials` token fetch fails | Decisions cannot be delivered; requests will queue |
| `scheduler` | A JIT sweep has not completed for ~5 min (they run every minute), or the hourly rule sweep for ~3 h | **Time-bound access silently never expires** |
| `approvalDrift` | Omnissa Access holds approval requests this queue has no pending record of | Those requesters wait forever; the app never provisions |
| `notifications` | Three consecutive webhook delivery failures | Approvers stop being told about new requests |

A component that cannot apply is simply absent — `notifications` does not appear
until something has actually been sent, and a tenant that has never been
configured reports `UP`, because a fresh install is a setup state rather than a
fault.

### The scheduler check earns its place

Every `@Scheduled` job in the tool shares Spring's default **single-threaded**
scheduler. If one wedges, the rest stop with it — and the JIT sweeps are what
revoke expired time-bound grants.

When that happens there is **no other symptom**: the container is up, the UI
works, the queue responds, Omnissa Access is reachable, and every other check is
green. The only visible consequence is that users quietly keep access they
should have lost. Nothing surfaces it, which is precisely why it is measured.

The heartbeat is recorded in a `finally` block, so a sweep that finds nothing to
do still counts as having run. Before a job's first execution the grace period
is measured from process start, so a container in its initial delay is not
reported as stalled.

### The drift check, and what it caught

Omnissa Access holds an approval open until it receives a decision. If the tool
has no record of a request, no decision can ever be sent: the requester waits
indefinitely and the application never provisions.

This is not hypothetical. Five requests — one of them the administrator's own —
sat stuck this way and presented as an Access *provisioning* fault, costing
significant time to diagnose. The cause there was deleting pending records
locally, which is now [refused](#deleting-a-pending-request-is-refused).

Deletion was only one route. The commoner one is **a callout that never
arrived**: a reverse-proxy misconfiguration, an outage, or rate limiting. Those
leave no trace whatsoever in the tool, so drift is the only way they surface.

When drift is detected the queue shows a banner, and **Pull from Access**
imports the missing requests so they can be decided.

> When the tenant is unreachable, drift cannot be evaluated. It is reported as
> unknown rather than zero, and it does **not** raise a second fault — the
> `omnissaAccess` component already reports that outage, and counting it twice
> would make one problem look like two.

### What a healthy `notifications` status does and does not prove

Webhooks cannot be actively probed: there is no ping, and "testing" one means
posting to the channel. So this component is retrospective — it reports the last
delivery outcome.

> **A success means the endpoint accepted the request, not that the message
> arrived.**
>
> Slack posts synchronously, so a success there is real. **Power Automate
> returns `202 Accepted`** — the trigger was queued. The flow can still fail, or
> silently drop the payload, long after the tool has recorded success.
>
> This is exactly how a Teams payload-shape bug hid: every dropped message
> logged as sent, and Teams received nothing for weeks while the tool reported
> healthy delivery throughout.

## Uptime Kuma

Kuma monitors are binary — up or down — so warning versus outage comes from
**which** monitor fires, each routed to a different notification.

| # | Type | URL | Meaning when red |
|---|---|---|---|
| **A** | HTTP(s) | `https://<host>/actuator/health` | **Outage.** The container is down. Alarm. |
| **B** | HTTP(s) Keyword | `https://<host>/api/health/deps`, keyword `"status":"UP"` | **Warning.** A dependency is unhealthy. |

Set **Retries** on both to debounce a single failed poll.

If Kuma runs outside the LAN, monitor **B** works with no reverse-proxy change
where `/api(/.*)?` is already whitelisted — it usually is, since
`/api/approvals/new` must be reachable for the Access callout.

Monitor **B** alone can serve both purposes if you prefer a single monitor: when
the container is down the endpoint stops answering entirely (connection refused,
or a 502 from the proxy), so *no response* means outage and a keyword miss means
degraded.

## Unified Access Gateway

Point the UAG health monitor at **`/actuator/health`**, never at the dependency
endpoint. UAG stops routing to a backend it considers unhealthy, so watching
dependencies there would take the tool offline during an Omnissa Access outage —
the exact failure the split prevents.

| Setting | Value |
|---|---|
| Health Check URI Path | `/actuator/health` |
| Expected | HTTP 200 |

**The UAG health monitor connects directly to the internal resource and does not
pass through the edge service's proxy pattern**, so `/actuator/health` does
*not* need to be added to `proxyPattern` for monitoring to work.

Only add it to the pattern if you want external clients to reach liveness
through the UAG. If you do, whitelist the exact path — **not** `/actuator(/.*)?`.
Only `health` is exposed today (`management.endpoints.web.exposure.include=health`),
but a broad pattern would silently publish anything enabled later, and other
actuator endpoints expose configuration and heap contents.

## Runbook

### `omnissaAccess` degraded

Decisions cannot reach the tenant. Requests continue to arrive and queue; they
are delivered once connectivity returns, so nothing is lost.

Check `/api/health/dependencies` for the error string, then verify the tenant
URL and that the service client's secret has not expired. The dashboard
connectivity tile shows the same probe.

### `scheduler` degraded

**Treat this as urgent**: time-bound grants are not being revoked, so access
persists past its TTL.

Check the log for a stack trace from `RuleScheduler`. Restarting the container
clears a wedged scheduler thread and the sweeps resume; expired grants are
picked up on the next pass, since the sweep selects on state and expiry rather
than tracking a cursor. Nothing is missed by the outage — only delayed.

### `approvalDrift` degraded

Open the queue and use **Pull from Access**, then decide the imported requests.
Until they are decided the requesters wait indefinitely.

If drift keeps recurring, callouts are not reaching the tool: check that
`POST /api/approvals/new` is reachable from the internet, that the URI in
**Settings > Approvals** is correct, and — behind a UAG — that Identity Bridging
is **off** for that path. See [Troubleshooting](troubleshooting.md).

### `notifications` degraded

Three consecutive delivery failures. Check `lastError` at
`/api/health/dependencies`. Common causes: a rotated or revoked webhook URL, a
Power Automate flow that has been turned off, or an expired Teams workflow.

Remember the caveat above — this component cannot tell you whether a *Teams*
message reached the channel, only that Power Automate accepted it.

## Deleting a pending request is refused

Deleting a request whose state is `pending` returns **HTTP 409**. Omnissa Access
is still waiting for a decision on it, and deleting the local record discards the
only means of answering — leaving the requester stuck permanently, with nothing
to indicate why.

Decline it first (a decline is always available, and can be temporary), then the
record deletes harmlessly. Deleting an already-decided record is unaffected.
