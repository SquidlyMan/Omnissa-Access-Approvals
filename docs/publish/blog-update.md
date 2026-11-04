---
title: "Access Approval Tool for Omnissa — October 2026 Update (1.23.4)"
author: "Dean Flaming"
date: "October 2026 • Omnissa Access, Workspace ONE, Lab Projects"
# Follow-up to the post of 2026-09-29 on the Omnissa Community forums:
# https://community.omnissa.com/forums/topic/73044-approvals-for-omnissa-access-without-the-guesswork/
# Posted as a separate update in the same topic.
title-page: false
section-breaks: false
---

![](assets/logo.png){.logo width="0.52in"}

> ### ⚠️ STILL UNSUPPORTED, STILL NON-PRODUCTION
>
> **This is not an Omnissa product.** It remains a personal lab project,
> provided **as-is with no warranty and no support of any kind**. Testing,
> labs and demonstrations only — **never production, never production data.**
> It changes real entitlements in whatever tenant you point it at.
> **All use is entirely at your own risk.** "Omnissa" and "Workspace ONE" are
> trademarks of Omnissa, LLC, used here only to describe interoperability.

A month after the original post, here is the first update: **1.23.4** shipped
on 21 October. Nothing about how the tool behaves has changed. What changed is
underneath it, in the documentation (two of the fixes came from readers of this
thread), and in how updates reach the project at all — which turned out to be
the most useful lesson of the month, so it gets its own section.

## What is in 1.23.4

- **Spring Boot 4.1.1**, and with it Spring Security 7.1.1, Spring Framework
  7.0.9, Tomcat 11.0.24, Jackson 3.1.5 and Hibernate 7.4.5.
- **springdoc-openapi 3.1.1**, the line built for Boot 4.1. It carries the
  swagger-ui DOMPurify fix for CVE-2026-75838, which matters here because
  `/swagger-ui/**` is served without a session, so it is reachable before
  anyone signs in.
- **React 19.3.1 and Vite 8.3.4** on the front end.
- No behaviour change; the suite is 472 tests. Images are published for amd64
  and arm64, and `1.23.4` and `latest` are the same digest.

## What you reported, fixed

- **Links from the documentation site to NOTICE, SECURITY and the deployment
  scripts were dead.** A reader of this post hit it first. Every guide linked
  those files with a parent-relative path (`../NOTICE.md`), which GitHub renders
  happily and which GitHub Pages cannot follow, because Pages serves only the
  `docs/` folder. Twenty links across nine guides now point at the files on
  GitHub. If you publish a project's docs the same way, a quick `grep` for
  `](../` is worth running before every docs merge.
- **The site's favicon returned 404 on every page.** The theme asks for
  `/favicon.ico` and nobody had ever shipped one. It was the only failing
  request left after a full link check of the site.
- **The guides overstated what an Access sign-in gives you.** The configuration
  and setup guides still said a user who signs in with Omnissa Access "receives
  full admin access". That has been wrong since roles arrived in 1.16.1: a
  successful sign-in grants **Viewer and nothing more** until a group named in
  `OMNISSA_ROLE_MAP` grants a higher role. Both guides say so now. It is the
  same point the original post made about the `group` scope, seen from the
  other side — no group claim, no role, and that is by design.

## Why the Boot 4.1 line was two months late (a Dependabot lesson)

Spring Boot 4.1.1 was released on 20 August. The project never got a pull
request for it, or for springdoc 3.1 or Vite 8.3, while Dependabot kept
proposing smaller things right on schedule. The contrast was the clue.

Back in July, when the project was still on Boot 3, springdoc 2, Vite 5 and
Tailwind 3, I had answered the major-version pull requests with
`@dependabot ignore this major version`. What that reply really does is store
a condition in GitHub's Dependabot state, not in `dependabot.yml`: *ignore
`spring-boot-starter-parent >=4,<5`*, and so on for the other three. The
project then moved onto those majors by hand, and from that day it was living
inside its own ignore ranges. Every update on the new line matched a
condition, and the only trace is one line in the Dependabot job log:
*"All updates for spring-boot-starter-parent were ignored."*

Three things I wish I had known, in case your repository has the same ghosts:

1. **Read the job log.** Insights → Dependency graph → Dependabot, then
   *Recent update jobs* on the row for the manifest. The job definition at the
   top of that log lists every standing ignore condition. The Dependabot tab
   returns 404 until the dependency graph is enabled under Settings → Advanced
   Security, which is why I had never looked there.
2. **`@dependabot unignore` is only honoured from an open pull request of that
   ecosystem.** Commenting on the closed July pull requests did nothing; their
   branches were long gone.
3. **Group minor and patch updates per ecosystem.** It is one pull request a
   week instead of several, and a grouped pull request is also where the
   unignore reply works.

The conditions are cleared, updates are grouped, and the next ones — Spring
Boot 4.1.2, springdoc 3.2.0 and Vite 8.3.5 — are due to arrive on their own at
the end of the month.

## Running it on ZimaOS? One note

The appliance behind the original post runs on a ZimaCube, and ZimaOS 1.8.0
landed this month. The tool came through untouched, but two things are worth
knowing if you run it, or anything else, on that platform:

- **1.8.0 rebuilds the Apps page from
  `/var/lib/casaos/apps/<project>/docker-compose.yml`.** A container deployed
  from anywhere else keeps running, keeps its restart policy, and loses its
  tile. On the appliance CasaOS already owned the compose file (the updater
  follows the container's own labels to find it), so the approvals tile stayed.
  A neighbouring app on the same box, deployed from its own folder, lost its
  tile — exactly as the upgrade's own log said it would.
- **Docker gives a container ten seconds to stop before it is killed.** An
  orderly stop, with the embedded database closing cleanly, can want a little
  longer, and at the ZimaOS reboot the approvals container was force-stopped. No harm done — the updater proves
  every deployment by image digest and reported version — but 1.23.5 adds a
  `stop_grace_period` of 30 seconds to the compose template.

## Updating

From the Dashboard: the update tile shows 1.23.4, an administrator clicks
**Approve…**, and the host-side updater pins the compose file to the exact
version, pulls, recreates, and proves the result by digest and reported
version, rolling back if either fails. If you run it with plain `docker run`,
pull `ghcr.io/squidlyman/omnissa-access-approvals:1.23.4` (or `latest`, which
now points at the same image) and recreate the container.

## What's Coming

1.23.5 is already shaped. It waits on one thing: Dependabot's grouped pull
requests, due at the end of the month now that the ignore ghosts are gone.
Here is the whole list, unglamorous parts included, with where each item
stands as I write this.

**Arriving on their own**

- **Spring Boot 4.1.2**, which brings Spring Security 7.1.2, Jackson 3.1.7 and
  the Spring Data 2026.0.2 train. A maintenance release with no security
  advisory, and I read the fix list so you do not have to: nothing in it
  changes how this tool behaves. The two Tomcat SSL fixes do not apply because
  TLS terminates at your reverse proxy, and the graceful-shutdown fix does not
  apply because the tool runs Boot's default shutdown.
- **springdoc-openapi 3.2.0**, built on Boot 4.1.2, with swagger-ui 5.34.0. Its
  release notes flag a few changes you might notice elsewhere — a new default
  for API versioning, Spring Data's `PagedModel` properties becoming required,
  Scalar's standalone integration no longer being disabled — and none of them
  touch this API: it has no versioned operations, its paging envelope is its
  own type, and Scalar is not on the classpath. The OpenAPI document should
  come out unchanged, and I will check that it does.
- **Vite 8.3.5**: build-tool fixes only. The SPA is rebuilt; nothing in it
  changes.

**Done by hand, in the same release**

- **The one open CodeQL finding, closed.** After a local sign-in the tool sends
  you back to the page you were on before the login screen interrupted you.
  Spring Security builds that destination from the saved request, and since
  1.23.3 only a real page navigation is saved, but the scanner still sees a
  redirect derived from request data. 1.23.5 will accept only a path on the
  same site and send anything else to the Dashboard — what the code always
  meant, and now something the scanner can prove. Tests will pin it, next to
  the ones from 1.23.3.
- **A 30-second stop window** (`stop_grace_period: 30s`) in the ZimaCube
  compose template, for the reason described above. If you run the container
  with plain `docker run`, the equivalent is `--stop-timeout 30`, and the
  deployment guide will say so.
- **This post, wired in.** The changelog will link to it, and its source moves
  into the repository's `docs/publish` folder, so it is rebuilt by the same
  pipeline as the documentation and the thread and the release history point
  at each other.
- **Housekeeping nobody will notice.** A leftover branch from the Dependabot
  grouping change goes away, and the slide decks' drift check gets a fresh
  baseline, because it has been grumbling about decks that were regenerated
  on purpose.

**How it ships**

The same way every release does: tag, build for amd64 and arm64, and a check
that `1.23.5` and `latest` are one and the same digest before the Dashboard
offers it. The appliance behind this post then updates exactly the way the
post describes — someone with a name in the audit trail clicks Approve.

**Not in 1.23.5**

The next real capability — configuring a ZimaCube deployment from the CasaOS
application settings instead of an environment file over SSH — is a separate
piece of work, and it lives with the rest of the plan in the release notes'
[What's Coming](https://github.com/SquidlyMan/Omnissa-Access-Approvals/blob/main/docs/publish/release-notes.md#whats-coming)
section. No dates, as usual.

## Resources

- **Release notes (1.23.4):** <https://github.com/SquidlyMan/Omnissa-Access-Approvals/blob/main/CHANGELOG.md#1234---2026-10-21>
- **Changelog:** <https://github.com/SquidlyMan/Omnissa-Access-Approvals/blob/main/CHANGELOG.md>
- **Repository (MIT):** <https://github.com/SquidlyMan/Omnissa-Access-Approvals>
- **Documentation site:** <https://squidlyman.github.io/Omnissa-Access-Approvals/>
- **Roles and RBAC:** <https://github.com/SquidlyMan/Omnissa-Access-Approvals#roles>
- **Container images:** `ghcr.io/squidlyman/omnissa-access-approvals`

As before, the repo takes issues — and the reader who caught the dead links
has my thanks.

> ### ⚠️ ONE MORE TIME
>
> **Unsupported. As-is. No warranty. Not an Omnissa product.**
> **Testing and demonstration only — never production, never production data.**
> **Entirely at your own risk.**
