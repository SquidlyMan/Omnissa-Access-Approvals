---
title: "Access Approval Tool for Omnissa"
subtitle: "What was built between v1.2 and v1.19.3"
author: "Dean Flaming (SquidlyMan)"
date: "MIT License"
---

> ### ⚠️ UNSUPPORTED — NON-PRODUCTION USE ONLY
>
> **Not an Omnissa product. Not supported by Omnissa. Provided as-is, without
> warranty.** For testing and demonstration only — never production, never
> production data. **Entirely at your own risk.**

At v1.2 this was a queue with two buttons. It received approval callouts from
Omnissa Access, showed them in a list, and posted approve or reject back. Every
administrator who could sign in could do everything. Every approval was
permanent.

The list below is what was added between then and v1.19.3, grouped by what it
does rather than by the release it arrived in. The version each item shipped in
is given in brackets.

## Access lifecycle

- **JIT / time-bound access** — approve for 5 minutes to 30 days; access is
  automatically revoked at expiry, which genuinely deprovisions the app in
  Omnissa Access [1.5.0]
- **Two after-expiry modes** — *re-requestable*, where the app returns to the
  catalog after a short hold, or *one-time*, where it stays gone [1.5.0]
- **Revocation that works for group-provisioned and directly-assigned apps
  alike**, using a per-user exclusion that overrides a group grant for one
  person without touching the group entitlement [1.5.0]
- **Permanent vs temporary decline** — a temporary reject lets the user ask
  again; a permanent one excludes them. **Allow re-request** reverses it [1.7.0]
- **A block that cannot be enforced is never recorded as though it were** — the
  audit trail does not claim an exclusion Access did not accept [1.7.0]
- **On-demand revoke**, without waiting for a TTL — *Revoke access* or *Revoke
  and block* [1.8.0]
- **Deployment Type captured at grant time and restored**, so returning access
  no longer silently converts an *Automatic* assignment to *User-Activated*
  [1.8.0]
- **Delete request** — administrator cleanup: two-step, local-only, audited
  [1.5.0] — later **refused while a request is still pending**, because Access
  holds the approval open until it receives a decision [1.18.0]

## Approvals from Slack and Microsoft Teams

- **Actionable Slack approvals** — duration menu and decision buttons, with the
  message updating in place [1.5.7]
- **Actionable Teams approvals** — Adaptive Cards delivered through a Power
  Automate workflow [1.9.0]
- **Deep links replaced in-chat callbacks on both platforms.** A chat signature
  proves the workspace, not the person, so authority had come from a separate
  approver list that drifted from Access and failed *open*. This removed an
  internet-facing unauthenticated endpoint, its signing secret, its replay
  window, and the list itself [1.16.1]
- **Decisions state their consequence, not their direction** — *"5 minutes,
  then requestable again"*, *"permanent: the user is blocked from
  re-requesting"* [1.16.1]
- **Lifecycle notifications** — the channel learns when a timed grant expires
  and when the app becomes requestable again [1.6.0]

## Governance and audit

- **Role-based access control resolved from Omnissa Access group membership** —
  Administrator, Approver, Viewer, Auditor. No second user directory [1.16.1]
- **Matched on group ids, not names**, so renaming a group in Access cannot
  silently drop everyone to Viewer. **Viewer is a fallback, not a floor**:
  configuring the map is the deliberate act that grants privilege [1.16.1]
- **Warning when Auditor is combined with another role** — it is the only
  restrictive role, so pairing it with any other silently defeats it [1.16.1]
- **Audit-trail CSV export**, gated separately from reading: a Viewer may read
  the trail on screen but not take a copy of it [1.16.1]
- **The requester recorded on every audit event**, so an entry still makes
  sense after the request it describes has been deleted. 176 historical events
  were backfilled [1.16.1]

## Accounts and sign-in

- **Local account management** — add, reset password, enable or disable, change
  roles, delete, all audited, plus self-service password change [1.18.0]
- **The last enabled local administrator cannot be disabled, deleted or
  demoted.** An Access user holding Admin through a group does not satisfy the
  guard — break-glass exists for exactly the case where Access sign-in is
  unavailable [1.18.0]
- **Password policy** [1.18.0], then **fully configurable**, with a minimum
  length floor that configuration can tighten but never remove [1.19.0]
- **Sign-in throttling with deliberately no account lockout.** Locking an
  account would let anyone who can reach the login page disable the one
  credential that exists for emergencies [1.19.0]

## Operability

- **Dependency health API** separating *"this container is down"* from
  *"something it depends on is unhealthy"* — the two need different responses,
  and CasaOS recreates the container when liveness fails [1.18.0]
- **Scheduler liveness check** — the one failure with no other symptom: if the
  expiry sweeps wedge, timed access silently never expires while everything
  else stays green [1.18.0]
- **Approval drift detection** — requests Access is holding that never reached
  the queue. Those requesters wait indefinitely with nothing to indicate why
  [1.18.0]
- **Pull from Access** — manual recovery of requests lost to a restart or a
  network gap; Access does not retry [1.3.0]
- **Backup and restore** — verified archives, retention, and a manifest
  recording the running image digest [1.5.0]
- **Version-tagged container images** [1.5.0] and a **test suite enforced as a
  CI gate** [v1.5.6]

## Deployment and platform

- **Configurable email sender** — the previous hardcoded address was rejected
  outright by most relays [1.2.1]
- **CasaOS tile points at the public URL** rather than the NAS address, so the
  registered OAuth redirect URI is used and the link is not blocked as mixed
  content [1.9.5]
- **Platform upgrade** — Spring Boot 4.1, React 19, Vite 8, Tailwind 4,
  TypeScript 6, with no intended behaviour or visual change [1.4.0]
- **In-app Help** gained condensed Slack and Teams setup walkthroughs [1.9.5]
  and a navigable contents list with back-to-top links

## Getting it running

- **It starts before you configure anything.** Omnissa Access OAuth and SMTP are
  both genuinely optional: stand the container up, sign in locally, confirm it
  serves, then point it at the tenant. Neither was optional before — a missing
  client-id or mail host prevented start-up outright, despite the configuration
  reference documenting the blank client-id as the way to run local-only
  [1.19.2]
- **Pages survive a refresh and a chat link.** Client routes were declared in
  two places that drifted, so a page could work when clicked and 404 when
  reloaded or opened from a Slack or Teams approval button. The server now
  serves the app to anything no controller, actuator or asset claimed, rather
  than matching a hand-kept list [1.19.2]

## The shape of it

Three things account for most of the above, and each came from the same
realisation: **the tool was reporting success while failing.**

- Approvals were permanent because nothing expired them — so access
  accumulated silently.
- Everyone who could sign in could do everything — so there was no way to say
  who *may* act, only who *did*.
- Chat buttons kept working for people whose access had been revoked — because
  authority lived in a second list that nobody reconciled.

Time-bound access, roles from Access groups, and deep links are the answers to
those three. The health and audit work exists to make the next such failure
visible rather than silent.
