# Published documents

Source for the public-facing blog post and the complete documentation PDF.

**Markdown is the single source.** The HTML, PDF and DOCX under `out/` are all
generated — do not edit them by hand, or the next build silently discards the
change.

That has already happened once, and it is worth knowing how. The rendered DOCX
are handed over as deliverables and were then edited in Word: a logo added, a
footer, a paragraph rewritten, a whole section brought up to date. None of it
was in the markdown, so rebuilding threw it away. **Anything changed in Word has
to come back here**, or the next build is a regression. Before overwriting a
handed-over copy, check who wrote it last:

```bash
unzip -p AccessApprovalToolBlogPost.docx docProps/app.xml | grep -o 'Microsoft Word'
```

```bash
./build.sh              # both documents
./build.sh blog-post    # just one
```

| File | Purpose |
|---|---|
| `blog-post.md` | Introductory blog post — leads with the unsupported / non-production disclaimer |
| `documentation.md` | Complete reference: features, deployment, tenant setup, configuration, POC walkthrough |
| `release-notes.md` | Per-version capabilities, fixes and known issues, plus what is planned. No dates — versions, not calendars |
| `feature-summary.md` | Everything built between v1.2 and v1.19.4, grouped by capability rather than release. For briefing someone who last saw v1.2 |
| `style.css` | Shared print/screen styling, so both documents look like one product |
| `reference.docx` | Word styling for the DOCX output — fonts, paragraph styles, page setup, running footer. Derived from a copy styled by hand in Word, with body content and images stripped, since pandoc reads only styling from a reference document |
| `assets/` | Diagrams and screenshots referenced by both documents, plus `logo.png` for the title pages |
| `check-slides.py` | Reports where the PowerPoint decks have fallen out of step. Read-only |
| `slides-fingerprint.json` | The decks' recorded formatting baseline — geometry, palette, fonts, type sizes |
| `out/` | Generated HTML, PDF and DOCX (git-ignored) |

## Requirements

```bash
brew install pandoc weasyprint
```

## Conventions

- **Image alt text is deliberately empty** (`![](assets/x.png)`). Pandoc turns a
  non-empty alt into a figure caption, which duplicates the italic caption line
  that follows each image.
- **A blockquote whose heading starts with ⚠ renders as a red warning block**;
  every other blockquote renders as an amber advisory note. `build.sh` tags them
  after conversion, since pandoc cannot express the distinction. This applies to
  the **DOCX as well as the HTML and PDF** — the legal disclaimer used to appear
  as an ordinary indented quotation in Word, which is the wrong emphasis on the
  one block whose purpose is to be noticed. The Word colours are read from
  `blockquote.danger` in `style.css` rather than chosen separately, so the two
  cannot drift.
- **Titles live in YAML frontmatter**, not as an `# H1` in the body — otherwise
  pandoc stacks its own title block above the heading.
- **`documentation.md` numbers its figures sequentially in document order.**
  Inserting a figure means renumbering every later caption; the blog post is
  short enough to use unnumbered captions instead.
- **Images are capped at 15 cm tall** in `style.css`. This is not cosmetic — a
  portrait dialog capture scaled to the full text column is taller than the
  printable area of a Letter page and would be clipped. Screenshots wider than
  they are tall need no attention; very tall captures should be cropped at
  build-prep time rather than relying on the cap alone.

## What `build.sh` fixes up after pandoc

Three things pandoc cannot express, applied to the finished files:

| Fix | Why it is not left to pandoc |
|---|---|
| The ⚠ blockquote becomes a warning panel (HTML/PDF and DOCX) | Pandoc has no notion of a hard warning versus an advisory note |
| A fully italic paragraph beneath an image becomes a caption | In HTML because `p > em:only-child` also matches a paragraph that merely *starts* with italics; in DOCX because pandoc leaves captions as body text. Italic alone is not the test — italic **and directly beneath an image** is, since both documents use a standalone italic sentence for emphasis in running prose |
| The DOCX footer is stamped with the `pom.xml` version | The footer comes from `reference.docx`, so it would otherwise be frozen at whatever release the reference was cut from |

## The PowerPoint decks

Two decks carry the same messaging as these documents:
`Access-Approval-Tool-for-Omnissa-Full-Deck.pptx` (50 slides) and
`Access-Approval-Tool-Summary-1Pager.pptx`. They live with the other
hand-delivered files, **outside this repository**, and they are edited by hand in
PowerPoint.

**They are never generated.** Pandoc's pptx writer emits placeholder slides and
would flatten the design completely: a single master, one layout, every slide
built from explicit shapes on a 0.62in margin grid, with a palette that
overrides the stock Office theme. `#132250` is the same navy as `style.css`,
which is why the documents and the decks read as one product.

So the decks are checked, not built:

```bash
./check-slides.py                 # report drift; changes nothing
./check-slides.py --fingerprint   # re-record the baseline after a deliberate redesign
./check-slides.py --decks <dir>   # point somewhere else, e.g. a broken copy to test with
```

`build.sh` runs it at the end as an advisory. It cannot fail a documents build.

| Check | Catches |
|---|---|
| Footer version | A footer still naming an old release. Only the footer — the body cites earlier versions constantly and correctly, and an earlier draft that flagged all nine of those was worse than no check |
| Configuration variables | The deck naming an environment variable that appears nowhere in the code or docs |
| Text growth | A text box whose content outgrew what it holds |
| Formatting | Any shape moved, resized, recoloured, or a font or type size changed — including the shape-tree transform, which offsets every shape on a slide at once |

**Why text growth matters:** neither deck sets autofit — no `normAutofit`, no
`spAutoFit` — so PowerPoint lets text spill outside its shape silently, and it
surfaces for the first time in front of an audience.

Capacity is **not** estimated from font metrics. That was tried, and calibrating
a glyph width until the current decks reported clean is fitting the constant to
the answer; it flagged six boxes that render correctly. What a box holds today is
observable, so the baseline records it and the check reports growth past it.

`slides-fingerprint.json` is that baseline: every slide's geometry, palette,
fonts and type sizes, hashed into ~16 KB of text instead of committing ten
megabytes of binary. Prose is deliberately excluded from the hash, so rewording
a slide does not read as a formatting break.

## Keeping them honest

These documents make specific claims about behaviour. When a claim stops being
true it becomes worse than useless, because a reader has no way to know. The
previous revision stated *"every authenticated administrator holds full rights;
no reviewer/read-only roles"* long after roles had shipped.

Re-check on any release that changes authorization, the access lifecycle, the
chat integrations, or the configuration surface.
