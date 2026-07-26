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
DOCS=("${@:-}")
[ -z "${DOCS[0]}" ] && DOCS=(blog-post documentation)

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
