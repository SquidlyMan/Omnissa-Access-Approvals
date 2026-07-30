---
title: "Approvals for Omnissa Access, Without the Guesswork"
author: "Dean Flaming"
date: "July 2026 • Omnissa Access, Workspace ONE, Lab Projects"
---

![](assets/logo.png){.logo width="0.52in"}

> ### ⚠️ READ THIS FIRST — UNSUPPORTED, NON-PRODUCTION
>
> **This is not an Omnissa product.** It is a personal lab project, provided
> **as-is with no warranty and no support of any kind** — not by Omnissa, and
> realistically not by me either beyond best effort.
>
> **Use it for testing, labs and demonstrations only. Never in production, and
> never with production data.** It changes real entitlements in whatever tenant
> you point it at. Point it at a tenant you are willing to break.
>
> **All use is entirely at your own risk.** "Omnissa" and "Workspace ONE" are
> trademarks of Omnissa, LLC, used here only to describe interoperability.

If you have ever flipped on the Approvals feature in Omnissa Access, you already
know the punchline: Access will happily require approval before a user can
activate an application, and it will happily POST each request to a REST
endpoint of your choosing… and then you discover nobody handed you the other
half. There is no reviewer console. No queue. No "click here to approve."
Access sends the callout into the void and waits for something on the far end
to answer.

For years the "something" was a dusty sample app or a promise to write one
later. I wanted a real answer for lab work and customer demos, so I built one:
the **Access Approval Tool for Omnissa** — a small, self-hosted approval gateway
you can stand up in an afternoon and demo the entire approvals flow end to end.

It started as a queue with two buttons. It has since grown into something I did
not entirely plan: time-bound access that expires on its own, approvals from
Slack and Teams, role-based access control driven by your Access groups, and
enough operational plumbing to tell you when it is quietly broken.

![](assets/architecture.png)

*The whole story on one picture: Access POSTs the callout in, decisions ride the
tenant API back out. Only one path needs to face the internet.*

## What It Is

A single Docker container: Spring Boot on the back, React on the front, an
embedded database inside, published to GitHub Container Registry. Point your
tenant's approval callout at it and you get a live queue of requests, a review
dialog that works from a phone, and decisions flowing straight back into Access
through the tenant API.

![](assets/queue-dashboard.png)

*A dashboard that counts what is waiting, and a queue that fills itself — new
requests arrive over Server-Sent Events, so nothing needs refreshing.*

Around that core loop it now carries a fair amount:

**Time-bound access.** Approve for five minutes, or eight hours, or thirty days.
When the clock runs out the tool revokes the entitlement in Access, which
genuinely deprovisions the app — the tile disappears from the user's catalog. You
choose what happens next: the app becomes requestable again after a short hold,
or it is a one-time grant that does not come back.

![](assets/approved-time-bound.png)

*Two controls, one decision. How long, and what happens when the clock runs out.*

**A decline that means something.** *Reject* is temporary — the user may ask
again. *Reject and block* excludes them from the application entirely, and stays
that way until an administrator lifts it. Same for revoking access you have
already granted: *Revoke access* hands it back to the catalog, *Revoke and
block* does not.

**Approvals from chat.** New requests post to Slack or Microsoft Teams with
Approve / Reject buttons. Those buttons are deep links — they open the request in
the tool, you sign in, and the decision is attributed to you. That is a
deliberate choice I will come back to.

![](assets/chat-slack.png)

*Slack. The channel also gets the follow-ups — auto-approvals, expiries, and
exclusions lifting — each stating what actually happened, not just that
something did.*

**Roles from your Access groups.** Administrator, Approver, Viewer, Auditor —
resolved from Omnissa Access group membership at sign-in, so there is no second
user directory to maintain. Put someone in a group in Access; they have the role
here.

**An audit trail that survives.** Every request and decision is recorded with
both who *acted* and who the access was *for*, so an entry still makes sense
after the request it describes has been deleted. Exportable as CSV, mirrored to
your syslog collector.

**Auto-approval rules**, wildcard app-name and group matching, first match wins,
with optional time-bound grants — plus auto-reject for requests left pending too
long. Both kinds scope the same way, so *"expire stale Finance requests after
three days"* is a rule you can actually write.

**Operational honesty.** A health endpoint that distinguishes "this container is
down" from "something it depends on is unhealthy", including a check for
approval requests Access is holding that never reached the queue. That last one
exists because it happened to me, and it looked exactly like an Access
provisioning fault for several days.

**It starts before you configure anything.** Omnissa Access OAuth and SMTP are
both genuinely optional, so you can stand the container up, sign in with the
local admin account, confirm it serves, and only then point it at a tenant.
That matters more than it sounds: when start-up depends on tenant
configuration, a configuration mistake and a deployment mistake look identical.

## Two Decisions Worth Explaining

**Chat buttons open the browser rather than deciding in place.** Slack can
deliver a decision straight from the message, and this tool did that originally.
The problem is authorization: an interaction callback arrives where nobody is
signed in. Slack signs the request, which proves it came from your workspace —
not that the person clicking may approve anything. So authority had to come from
a separate list of approvers, and that list drifted from reality. Removing
someone in Access revoked their web access instantly and left their Slack buttons
working.

Deep links remove the divergence rather than manage it. They also delete an
internet-facing endpoint, its signing secret and its replay window. Fewer moving
parts, one set of rules.

**No account lockout on the login form.** Failed sign-ins are progressively
delayed, and a persistent source is refused — but an account is never locked.
Local sign-in is the way back in when Access is unreachable, and locking it after
N failures would let anyone who can reach the login page disable exactly the
credential you need in an emergency.

## What It Is Not

Let me save you a change-request ticket.

This is **not** an Omnissa product, and Omnissa does not support it — the
trademark belongs to them, the bugs belong to me. It is not an ITSM platform:
there are no multi-stage approval chains, no delegation trees, no SLA engine
wearing a tie. It will not replace ServiceNow, and it is not trying to.

One tenant, one container, an embedded database — deliberately simple, because
its job is to make the Access approvals capability *visible and testable*, not to
run your enterprise. If your POC succeeds and you want approvals in production,
wire Access to a real workflow platform and buy the team pizza.

## Deploying It

High level, four moves:

**1. Run the container.** Pull `ghcr.io/squidlyman/omnissa-access-approvals:latest`,
hand it an env file, mount a data volume, and put a TLS reverse proxy in front.
Nothing below is needed to get this far — it will start with no tenant
configured, so you can confirm it serves before involving Access at all.
One path — `/api/approvals/new` — must be reachable from the internet, because
the Access cloud does the POSTing. The admin UI can stay on your LAN.

```bash
docker pull ghcr.io/squidlyman/omnissa-access-approvals:latest

docker run -d --name omnissa-approvals -p 8081:8081 \
  --env-file ./omnissa-approvals.env \
  -v ./data:/app/data \
  --restart unless-stopped \
  ghcr.io/squidlyman/omnissa-access-approvals:latest
```

**2. Create two OAuth clients** in your tenant: a service client (client
credentials) so the tool can post decisions back, and an OIDC client so admins
can sign in with their Access identity. Two gotchas worth the price of
admission: the OIDC issuer is `https://<tenant>/SAAS/auth` — never `/acs` — and
the client needs the **`group`** scope, or no group claim is emitted and everyone
silently lands as a Viewer.

**3. Enable approvals** in the Access console: Approval Engine = REST API, URI =
your public hostname plus `/api/approvals/new`. Saving fires a probe at your
endpoint, so a green save means connectivity is proven.

**4. Gate an app.** Enable *License Approval Required* on a test application,
assign it User-Activated, request it as a user, and watch the request appear in
the queue in real time.

![](assets/hub-pending.png)

*The user side: PENDING until somebody — or a rule — says yes.*

Updates are a `compose pull` away, and there is an opt-in Watchtower profile for
daily auto-updates, shipped disabled and scoped so it can only touch this one
container.

## Resources

- **Repository (MIT):** <https://github.com/SquidlyMan/Omnissa-Access-Approvals>
- **Documentation site:** <https://squidlyman.github.io/Omnissa-Access-Approvals/>
- **Deployment guide:** <https://squidlyman.github.io/Omnissa-Access-Approvals/deployment.html>
- **Tenant setup (with screenshots):** <https://squidlyman.github.io/Omnissa-Access-Approvals/omnissa-access-setup.html>
- **Roles and RBAC:** <https://github.com/SquidlyMan/Omnissa-Access-Approvals#roles>
- **Monitoring:** <https://squidlyman.github.io/Omnissa-Access-Approvals/monitoring.html>
- **Configuration reference:** <https://squidlyman.github.io/Omnissa-Access-Approvals/configuration.html>
- **Troubleshooting:** <https://squidlyman.github.io/Omnissa-Access-Approvals/troubleshooting.html>
- **Container images:** `ghcr.io/squidlyman/omnissa-access-approvals`
- **Issues and bugs:** <https://github.com/SquidlyMan/Omnissa-Access-Approvals/issues>

The in-app Help page carries the same documentation, so once it is running you
never have to leave the browser.

Go stand one up, gate an app, and finally see what that Approvals toggle actually
does. And if you break it in an interesting way, the repo takes issues — bring
logs.

> ### ⚠️ ONE MORE TIME
>
> **Unsupported. As-is. No warranty. Not an Omnissa product.**
> **Testing and demonstration only — never production, never production data.**
> **Entirely at your own risk.**
