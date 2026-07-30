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

# The project version, taken from pom.xml rather than retyped, so the footer of
# every rendered document matches the release it was built from.
VERSION=$(sed -n 's/.*<version>\([0-9][^<]*\)<\/version>.*/\1/p' ../../pom.xml | head -1)
[ -n "$VERSION" ] || { echo "could not read <version> from pom.xml"; exit 1; }
echo "    version $VERSION"

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

  # reference.docx supplies the Word look: fonts, paragraph styles, page setup
  # and the running footer. It was derived from a copy styled by hand in Word,
  # with the body content and images stripped — pandoc reads only the styling
  # from it. Without this the DOCX comes out in pandoc's stock template, which
  # is not what these documents are handed over as.
  pandoc "$doc.md" \
    --standalone \
    --reference-doc=reference.docx \
    -o "out/$doc.docx"

  # Two fixes pandoc cannot express, applied to the finished DOCX:
  #
  #  1. The footer in reference.docx names a version, so it is frozen at
  #     whatever release the reference was made from. Stamp the real one in, or
  #     the documents keep claiming an old version while their contents move on.
  #  2. A paragraph that is ENTIRELY italic is a figure caption and belongs in
  #     the ImageCaption style. This is the same rule the HTML step applies, for
  #     the same reason — pandoc leaves these as body text, which reads as
  #     ordinary prose sitting under a screenshot.
  python3 - "out/$doc.docx" "$VERSION" <<'PY'
import re, shutil, sys, zipfile

path, version = sys.argv[1], sys.argv[2]
tmp = path + ".tmp"
stamped = 0
captions = 0
prev_had_image = False

PARA = re.compile(r"<w:p\b.*?</w:p>", re.S)
RUN   = re.compile(r"<w:r\b.*?</w:r>", re.S)


def all_italic(para):
    """A paragraph whose every text-bearing run is italic."""
    texts = [r for r in RUN.findall(para) if "<w:t" in r]
    if not texts:
        return False
    return all("<w:i/>" in r or '<w:i ' in r for r in texts)


def has_image(para):
    return "<w:drawing" in para or "<w:pict" in para


def restyle(para):
    """Caption = entirely italic AND directly beneath an image.

    Italic alone is not enough: both documents use a standalone italic sentence
    for emphasis in running prose, and styling those as captions centres and
    greys real body text. The figure above is what makes it a caption.
    """
    global captions, prev_had_image
    is_caption = all_italic(para) and prev_had_image
    prev_had_image = has_image(para)
    if not is_caption:
        return para

    # Every pattern here tolerates the space pandoc puts before "/>". Matching
    # the exact spelling `"/>` silently matched nothing, and because the counter
    # incremented on intent rather than on effect, the build cheerfully reported
    # styling captions it had not touched. Count the result, never the intent.
    if "<w:pStyle" in para:
        out = re.sub(r'<w:pStyle w:val="[^"]*"\s*/>',
                     '<w:pStyle w:val="ImageCaption"/>', para, count=1)
    elif re.search(r"<w:pPr\s*>", para):
        out = re.sub(r"(<w:pPr\s*>)", r'\1<w:pStyle w:val="ImageCaption"/>',
                     para, count=1)
    else:
        out = re.sub(r"(<w:p\b[^>]*?>)",
                     r'\1<w:pPr><w:pStyle w:val="ImageCaption"/></w:pPr>',
                     para, count=1)

    if out != para:
        captions += 1
    return out


with zipfile.ZipFile(path) as zin, \
     zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if re.fullmatch(r"word/footer\d*\.xml", item.filename):
            # Count what MATCHED, not what changed. Rebuilding at the same
            # version the reference was made from is a no-op substitution, and
            # treating that as "nothing to stamp" would warn on the one case
            # that is definitely fine.
            new, hits = re.subn(r"v\d+\.\d+(?:\.\d+)?", f"v{version}",
                                data.decode("utf-8"))
            stamped += hits
            data = new.encode("utf-8")
        elif item.filename == "word/document.xml":
            data = PARA.sub(lambda m: restyle(m.group(0)),
                            data.decode("utf-8")).encode("utf-8")
        zout.writestr(item, data)

shutil.move(tmp, path)
print(f"    captions styled: {captions}")
if not stamped:
    # Not fatal, but say so: a silently unstamped footer is the failure this
    # step exists to prevent.
    print(f"    WARNING: no version found in the footer of {path}", file=sys.stderr)
PY

  printf "    %-28s %s\n" "out/$doc.html" "$(du -h "out/$doc.html" | cut -f1)"
  printf "    %-28s %s\n" "out/$doc.pdf"  "$(du -h "out/$doc.pdf"  | cut -f1)"
  printf "    %-28s %s\n" "out/$doc.docx" "$(du -h "out/$doc.docx" | cut -f1)"
done
