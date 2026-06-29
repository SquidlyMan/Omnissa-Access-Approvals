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
- **App version:** <e.g. 1.2.3 (456)>
- **iPadOS version:** <e.g. 17.5>
- **Device:** <e.g. iPad Pro 11" (M4)>
- **Orientation / mode:** <portrait | landscape | Stage Manager | Split View | Slide Over>
- **Account / plan:** <Free | Pro | Team | etc.>
- **Session type:** Remote-controlled Claude Code session running in a terminal
  on macOS (latest version), viewed/controlled through the Claude iPadOS app.

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
<Always | Often | Intermittent> — appears tied to long threads; happens when
selecting near the most recent response.

## Screenshots / Recordings
<Drop image/video files alongside this report and link them here, e.g. `![](./issue-01-1.png)`. A screen recording of the auto-scroll/jump is ideal.>

## Workaround
<Any temporary workaround, or "None known".>

## Additional Notes
- Reproduced specifically in long threads; short threads may not trigger it.
- The "jump around" suggests the scroll position is being reset/recalculated
  (possibly on content re-render or selection-change events).
