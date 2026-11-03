---
title: "Access Approval Tool for Omnissa — Two Corrections, and an Update"
author: "Dean Flaming"
date: "November 2026 • Omnissa Access, Workspace ONE, Lab Projects"
# Reply to the October 2026 update (blog-update.md) in the same Omnissa
# Community topic:
# https://community.omnissa.com/forums/topic/73044-approvals-for-omnissa-access-without-the-guesswork/
# It corrects two claims in that update — Dependabot unignore on closed pull
# requests, and why the container was force-stopped at the ZimaOS reboot —
# and records what 1.23.5 does about the second.
title-page: false
section-breaks: false
---

![](assets/logo.png){.logo width="0.52in"}

**Two corrections, and an update (2 November)**

I got two things wrong in the update above, so here are the fixes, along with
some progress.

**`@dependabot unignore` does work on closed pull requests.** I wrote that it
is only honoured from an open pull request, and that commenting on the closed
July pull requests did nothing. It did work. I posted the unignore comments on
those closed pull requests, branches long gone, and Dependabot answered each
one with "OK, I will stop ignoring…" — seven to seventeen minutes later. I
missed the replies and repeated the commands on the new grouped pull request,
which then found nothing left to clear. So the real lesson is simpler: unignore
on the pull request where you gave the ignore, even a closed one, and give it a
quarter of an hour. Grouping minor and patch updates is still worth doing, for
the one-pull-request-a-week reason alone.

**Why the container was force-stopped at the ZimaOS reboot.** I put it down to
the embedded database wanting a little longer to close, and I wrote that Spring
Boot's graceful-shutdown fix did not apply because the tool runs Boot's default
shutdown. Boot's default *is* graceful, and that is the actual story. On a
stop, the tool waits for open requests to finish, and an open Dashboard or
Queue tab holds a live-update stream that never finishes by itself. So the tool
sits out Boot's full 30-second shutdown phase, and Docker's ten seconds run out
first. Measured on 1.23.4 and on the new build alike: about 32 seconds to exit
with one tab open, under a second with none. The database was never the
problem, and the 30-second stop window I promised would not have been enough
either.

So 1.23.5 fixes the cause instead: the tool closes its live-update streams as
soon as shutdown begins, so a stop with tabs open is as quick as one without.
The compose template still gets a stop window, 15 seconds, purely as margin.

**The dependency half of 1.23.5 has landed.** Dependabot opened both grouped
pull requests at the end of October, right on schedule and with no ignore
conditions left — Spring Boot 4.1.2 with springdoc 3.2.0, and Vite 8.3.5 — and
both are merged, with the suite green at 472 tests. I said I would check the
OpenAPI document: it is unchanged. This time I also went through Boot 4.1.2's
whole fix list against a running build. A few fixes do touch code the tool
runs — graceful shutdown, how the executable jar loads its libraries, log
colouring, and Tomcat's SSL setup in the standalone HTTPS mode — and none of
them changes what it does. Tomcat moves from 11.0.24 to 11.0.27, which closes
a batch of Tomcat advisories. One is on the tool's request path,
CVE-2026-77756 (low): an HTTP/1.0 request that carries a Transfer-Encoding
header is now rejected. In the standalone HTTPS mode, CVE-2026-93446 (low,
intermittent TLS 1.3 failures) may apply as well. The rest need features the
tool does not use, such as AJP, HTTP/2, WebSocket endpoints, JSP or Tomcat's
own authentication.

**One addition.** springdoc 3.2.0 brings swagger-ui 5.34.0, which bundles
DOMPurify 3.4.16, and three DOMPurify advisories published on 22 October apply
to that version. None is reachable through the way swagger-ui uses DOMPurify,
but `/swagger-ui/**` is served before anyone signs in, so 1.23.5 takes
swagger-ui 5.34.1, with the fixed DOMPurify 3.4.17, rather than leave a scanner
finding on a public path.

The rest of the list above stands. No dates, as usual.
