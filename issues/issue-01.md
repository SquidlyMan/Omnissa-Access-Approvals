# [Issue 01] Selecting text in a long thread auto-scrolls to top and deselects

## Summary
While viewing a remote-controlled Claude Code session inside the Claude iPadOS
app, attempting to highlight/select text near the most recent response in a long
thread causes the view to automatically scroll back to the top of the thread and
jump around, deselecting the text in the process.

## Severity
Medium — Does not lose data, but makes it effectively impossible to copy text
from recent responses in long sessions, which is a core interaction.

## Environment
- **App:** Claude for iPadOS
- **App version:** Latest version available as of 2026-06-29 <fill in exact build #>
- **iPadOS version:** Latest version available as of 2026-06-29 <fill in exact #>
- **Device:** <e.g. iPad Pro 11" (M4)>
- **Orientation / mode:** Full screen only (not Split View, not Stage Manager). Reproduces in both portrait and landscape — orientation does not matter.
- **Account / plan:** <Free | Pro | Team | etc.>
- **Session type:** Remote-controlled Claude Code session running in a terminal
  on macOS (latest version), viewed/controlled through the Claude iPadOS app.

## Scope / Affected Configurations
This issue is specific to **remote-controlled Claude Code sessions whose remote
host is macOS**. It does **not** reproduce on:
- Remote Claude Code sessions running on other (non-macOS) systems.
- Claude Code sessions running locally through the Claude iPadOS app (i.e. not
  remote-controlled).
- The **Claude iPhone app** — the same kind of remote macOS session does **not**
  exhibit this bug on iPhone.

This strongly suggests the bug is specific to the **iPadOS** app's handling of
the macOS remote session view, rather than to text selection in threads in
general or to the Claude apps as a whole.

## Steps to Reproduce
1. Start or open a remote-controlled Claude Code session (terminal on macOS, latest version) in the Claude iPadOS app.
2. Let the thread grow long (enough content that it scrolls well beyond one screen).
3. Scroll down to the most recent response at the bottom of the thread.
4. Touch and hold to begin selecting/highlighting text near that recent response.

## Expected Behavior
The text I touch should be selected and stay selected, with the view holding its
scroll position so I can adjust the selection handles and copy.

## Actual Behavior
The thread automatically scrolls back up to the top, the view jumps around, and
my text selection is cleared.

## Frequency
Always — happens every time for sessions matching these parameters (remote macOS
session, long thread, selecting near the most recent response in the iPadOS app).

## Screenshots / Recordings
A screen recording of the auto-scroll/jump behavior exists but has **not** been
attached: it could not be compressed under the 30 MB upload limit of the Claude
Code session. The recording can be provided separately on request.

## Workaround
No in-app workaround found on iPadOS. The only way around it is to use a
different client entirely — the macOS version or the Claude iPhone app, neither
of which exhibits the issue.

## Additional Notes
- Reproduced specifically in long threads; short threads may not trigger it.
- Reproduces **only** on remote macOS sessions in the iPadOS app — not on remote
  non-macOS sessions, not on local (non-remote) iPadOS sessions, and not on the
  Claude iPhone app.
- The "jump around" suggests the scroll position is being reset/recalculated
  (possibly on content re-render or selection-change events).
- Occurs on the latest iPadOS and latest Claude iPadOS app as of 2026-06-29.
