#!/usr/bin/env bash
# Renders the published documents from their markdown source.
#
#   ./build.sh            # both documents
#   ./build.sh blog-post  # just one
#
# Outputs HTML (self-contained), PDF (WeasyPrint) and DOCX (pandoc) into out/.
# Markdown is the single source; nothing is edited in the rendered files.
set -euo pipefail
cd "$(dirname "$0")"

command -v pandoc     >/dev/null || { echo "pandoc not found (brew install pandoc)"; exit 1; }
command -v weasyprint >/dev/null || { echo "weasyprint not found (brew install weasyprint)"; exit 1; }

mkdir -p out

# Assets are copies of ../images. Copying them by hand once is how a redacted
# screenshot silently fails to reach the rendered PDF: the source gets fixed,
# the stale copy keeps rendering, and nothing complains. Re-sync on every build
# so the published documents cannot lag the reviewed originals.
python3 - <<'PY'
import hashlib, shutil
from PIL import Image

# publish asset  <-  ../images source
COPY = {
    "hub-pending.png":              "hub-request-pending.png",
    "access-oidc-client.png":       "access-oauth-admin-client.png",
    "access-service-client.png":    "access-oauth-service-client.png",
    "access-assignment.png":        "access-assignment-user-activated.png",
    "access-license-approval.png":  "access-license-approval.png",
    "access-approvals-settings.png":"access-approvals-settings.png",
    "queue-dashboard.png":          "tool-queue-review.png",
    "review-dialog.png":            "app_review_dialog.png",
    "approved-time-bound.png":      "app_approved_time_bound.png",
    "approved-revoke.png":          "app_approved_revoke_delete.png",
    "revoke-and-block.png":         "app_revoke_and_block.png",
    "reject-options.png":           "app_rejection_options.png",
    "allow-re-request.png":         "app_allow_re-request.png",
    "rules.png":                    "tool-rules.png",
    "expiry-rule.png":              "app_expiry_rule.png",
    "chat-slack.png":               "app_slack_messages.png",
    "chat-teams.png":               "app_teams_messages.png",
    "users.png":                    "tool-users.png",
    "delete-confirm.png":           "app_request_delete_confirm.png",
    "help-contents.png":            "tool_help_overview.png",
}
digest = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()

changed = []
for dst, src in COPY.items():
    d, s = f"assets/{dst}", f"../images/{src}"
    try:
        if digest(d) == digest(s):
            continue
    except FileNotFoundError:
        pass
    shutil.copy2(s, d)
    changed.append(dst)

# The audit capture is 2846x5400 — scaled to the text column it is an
# unreadable sliver, so it is cropped to the top rather than merely resized.
img = Image.open("../images/tool-audit.png")
crop = img.crop((0, 0, img.width, 2760))
crop.resize((1600, round(1600 * crop.height / crop.width)), Image.LANCZOS) \
    .save("assets/audit-trail.png", optimize=True)

if changed:
    print("    refreshed from ../images: " + ", ".join(sorted(changed)))
PY

DOCS=("${@:-}")
[ -z "${DOCS[0]}" ] && DOCS=(blog-post documentation release-notes feature-summary)

for doc in "${DOCS[@]}"; do
  [ -f "$doc.md" ] || { echo "no such document: $doc.md"; exit 1; }
  echo "==> $doc"

  # HTML — self-contained so it can be posted or emailed as one file.
  pandoc "$doc.md" \
    --standalone --embed-resources \
    --css=style.css \
    \
    -o "out/$doc.html"

  # A blockquote whose first heading carries the alert mark is a hard warning,
  # not an advisory note. Pandoc cannot express that, so tag it afterwards.
  python3 - "out/$doc.html" <<'PY'
import re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8").read()

html = re.sub(r'<blockquote>(\s*<h[1-6][^>]*>[^<]*⚠)',
              r'<blockquote class="danger">\1', html)

# Tag paragraphs that are ENTIRELY italic as captions. This has to happen here
# rather than in CSS: `p > em:only-child` also matches a paragraph that merely
# begins with italics, because text nodes are not counted by :only-child.
html = re.sub(r'<p><em>((?:(?!</em>|<p[ >]).)*?)</em></p>',
              r'<p class="caption"><em>\1</em></p>', html, flags=re.S)

open(path, "w", encoding="utf-8").write(html)
PY

  weasyprint "out/$doc.html" "out/$doc.pdf" 2>/dev/null

  pandoc "$doc.md" \
    --standalone \
    \
    -o "out/$doc.docx"

  printf "    %-28s %s\n" "out/$doc.html" "$(du -h "out/$doc.html" | cut -f1)"
  printf "    %-28s %s\n" "out/$doc.pdf"  "$(du -h "out/$doc.pdf"  | cut -f1)"
  printf "    %-28s %s\n" "out/$doc.docx" "$(du -h "out/$doc.docx" | cut -f1)"
done
