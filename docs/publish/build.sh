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

# A full-page audit capture scaled to the text column is an unreadable sliver,
# so it is cropped to the top rather than merely resized. Clamp to the source
# height: PIL pads a crop that runs past the edge instead of clipping it, so an
# already-short capture would otherwise gain a black band the width of the page.
img = Image.open("../images/tool-audit.png")
crop = img.crop((0, 0, img.width, min(img.height, 2760)))
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
disclaimers = 0
prev_had_image = False
in_block = False

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


# The legal disclaimer must read as a warning in Word exactly as it does in the
# PDF, so these are the colours from blockquote.danger in style.css and nothing
# invented here. Word border widths are eighths of a point: the 5px CSS rule is
# sz=30, the 1px frame is sz=8.
SHADE      = "FDECEA"   # faded red panel
FRAME      = "E8B4AE"   # 1px surround
RULE       = "C0392B"   # 5px left rule
HEAD_COLOR = "A5271A"   # the ⚠ heading
BOLD_COLOR = "8F1F14"   # emphasised text inside the block

PBDR = (f'<w:pBdr>'
        f'<w:top w:val="single" w:sz="8" w:space="4" w:color="{FRAME}"/>'
        f'<w:left w:val="single" w:sz="30" w:space="6" w:color="{RULE}"/>'
        f'<w:bottom w:val="single" w:sz="8" w:space="4" w:color="{FRAME}"/>'
        f'<w:right w:val="single" w:sz="8" w:space="4" w:color="{FRAME}"/>'
        f'</w:pBdr>')
PSHD = f'<w:shd w:val="clear" w:color="auto" w:fill="{SHADE}"/>'

# In CT_PPr these belong after pStyle/keepNext/numPr and before spacing/ind/jc.
# Word tolerates a lot, but an out-of-order child is the kind of thing that
# opens fine here and is rejected on someone else's build.
PPR_LATER = re.compile(r"<w:(spacing|ind|jc|contextualSpacing|rPr)\b")


def style_of(para):
    m = re.search(r'<w:pStyle w:val="([^"]+)"', para)
    return m.group(1) if m else ""


def text_of(para):
    return " ".join(re.sub(r"<[^>]+>", " ", para).split())


def paint(para, head):
    """Give one paragraph the warning panel, and colour its runs."""
    # Panel: borders + shading into w:pPr, creating w:pPr if pandoc omitted it.
    if re.search(r"<w:pPr\s*>", para):
        def ins(m):
            inner = m.group(2)
            hit = PPR_LATER.search(inner)
            at = hit.start() if hit else len(inner)
            return m.group(1) + inner[:at] + PBDR + PSHD + inner[at:] + m.group(3)
        para = re.sub(r"(<w:pPr\s*>)(.*?)(</w:pPr>)", ins, para, count=1, flags=re.S)
    else:
        para = re.sub(r"(<w:p\b[^>]*?>)", r"\1<w:pPr>" + PBDR + PSHD + "</w:pPr>",
                      para, count=1)

    colour = HEAD_COLOR if head else BOLD_COLOR

    def run(m):
        r = m.group(0)
        if "<w:t" not in r:
            return r
        bold = "<w:b/>" in r or "<w:b " in r
        # The heading is coloured throughout. Body text follows the PDF, where
        # only the emphasised parts turn red and the rest stays ordinary — the
        # disclaimer is mostly bold already, which is what makes it read red.
        if not head and not bold:
            return r
        tag = f'<w:color w:val="{colour}"/>'
        if "<w:rPr>" in r:
            return r.replace("<w:rPr>", "<w:rPr>" + tag, 1)
        return re.sub(r"(<w:r\b[^>]*?>)", r"\1<w:rPr>" + tag + "</w:rPr>", r, count=1)

    return RUN.sub(run, para)


def disclaimer(para):
    """Paint the ⚠-headed blockquote: its heading plus the quoted paragraphs.

    Matches the HTML step's rule exactly — a blockquote is a hard warning only
    when its heading carries the alert mark. The softer `⚠ Upgrade note`
    blockquotes have no heading and stay as they are, in both formats.
    """
    global disclaimers, in_block
    style = style_of(para)
    head = style.startswith("Heading") and "⚠" in text_of(para)

    if head:
        in_block = True
    elif in_block and style != "BlockText":
        in_block = False
        return para
    elif not in_block:
        return para

    out = paint(para, head)
    if out != para:
        disclaimers += 1
    return out


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
            data = PARA.sub(lambda m: disclaimer(restyle(m.group(0))),
                            data.decode("utf-8")).encode("utf-8")
        zout.writestr(item, data)

shutil.move(tmp, path)
print(f"    captions styled: {captions}, disclaimer paragraphs: {disclaimers}")
if not disclaimers:
    # Every one of these documents carries a legal disclaimer. Zero means the
    # rule stopped matching, and an unstyled disclaimer is the one thing here
    # that has to be impossible to miss.
    print(f"    WARNING: no ⚠ disclaimer block found in {path}", file=sys.stderr)
if not stamped:
    # Not fatal, but say so: a silently unstamped footer is the failure this
    # step exists to prevent.
    print(f"    WARNING: no version found in the footer of {path}", file=sys.stderr)
PY

  printf "    %-28s %s\n" "out/$doc.html" "$(du -h "out/$doc.html" | cut -f1)"
  printf "    %-28s %s\n" "out/$doc.pdf"  "$(du -h "out/$doc.pdf"  | cut -f1)"
  printf "    %-28s %s\n" "out/$doc.docx" "$(du -h "out/$doc.docx" | cut -f1)"
done

# The slide decks carry the same messaging as these documents, so a docs build is
# the natural moment to notice they have fallen behind. Advisory only, and
# deliberately so: the decks are hand-built in PowerPoint, their design cannot be
# regenerated, and nothing here should rewrite them. `|| true` keeps a drifted
# deck from failing a documents build it has no bearing on.
echo "==> slide decks"
./check-slides.py || true
