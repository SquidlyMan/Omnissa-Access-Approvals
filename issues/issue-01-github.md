<!--
PASTE-READY GITHUB ISSUE for anthropics/claude-code
- Title: use the single line in the "TITLE" block below (do NOT include the word "TITLE").
- Body: copy everything under the "BODY" line into the issue description box.
- Before submitting: (1) paste your video link, (2) set your account/plan.
-->

=== TITLE ===
iPadOS app: selecting text in a long remote macOS session auto-scrolls to top and deselects

=== BODY ===
### Summary
In the **Claude iPadOS app**, while viewing a **remote-controlled Claude Code session whose host is macOS**, attempting to highlight/select text near the most recent response in a long thread causes the view to automatically scroll back to the top of the thread and jump around, deselecting the text in the process. This makes it effectively impossible to copy text from recent responses.

### Environment
| | |
|---|---|
| **Client** | Claude for iPadOS |
| **App version** | 1.260618.1 (27978583058) |
| **iPadOS** | 26.5 |
| **Device** | iPad Pro 13-inch (M4) — model MWT13LL/A (A2926) |
| **Orientation / mode** | Full screen only (no Split View, no Stage Manager); reproduces in both portrait and landscape |
| **Input** | Reproduces both with and without the Magic Keyboard + trackpad |
| **Account / plan** | <Free / Pro / Max / Team — fill in> |
| **Session** | Remote-controlled Claude Code session in a terminal on macOS (latest), controlled via the iPadOS app |

### Steps to reproduce
1. Open a remote-controlled Claude Code session (terminal on macOS, latest version) in the Claude iPadOS app.
2. Let the thread grow long (content scrolls well beyond one screen).
3. Scroll down to the most recent response at the bottom of the thread.
4. Touch and hold (or use the trackpad) to begin selecting text near that recent response.

### Expected behavior
The text I select stays selected, and the view holds its scroll position so I can adjust the selection handles and copy.

### Actual behavior
The thread automatically scrolls back to the top, the view jumps around, and the text selection is cleared.

### Frequency
**Always** — every time, for sessions matching the parameters above.

### Scope (what it does and doesn't affect)
- ✅ Reproduces: remote macOS sessions viewed in the **iPadOS** app.
- ❌ Does **not** reproduce: remote sessions on non-macOS hosts.
- ❌ Does **not** reproduce: local (non-remote) sessions in the iPadOS app.
- ❌ Does **not** reproduce: the **iPhone** Claude app (same kind of remote macOS session works fine).

This points to the iPadOS app's handling of the macOS remote-session view specifically, rather than text selection in general.

### Workaround
No in-app workaround on iPadOS. The only way around it is to switch clients — the macOS app or the iPhone app, neither of which has the issue.

### Video evidence
<paste link here — YouTube (unlisted) / iCloud / Google Drive / Dropbox. Or drag a clip directly into this box if it's under your account's size limit: 10 MB free, 100 MB paid.>

### Related issues (not duplicates)
A search found no exact duplicate. Related scroll-to-top reports, none combining "iPadOS app + user text selection + idle long thread + remote macOS only":
- #28991 — Remote Control (iOS app): `AskUserQuestion` text truncated. _Same mobile-app + remote-macOS family, different symptom._
- #35177 — Terminal output pushes scroll position; can't select/copy. _CLI (iTerm2), triggered by output generation._
- #47643 — Switching conversations in Code tab scrolls to top. _macOS desktop app; closed as not planned._
- #34765, #34400, #33814, #36582 — Scroll resets to top during processing. _CLI/terminal, triggered by active generation._
