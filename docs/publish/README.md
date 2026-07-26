# Published documents

Source for the public-facing blog post and the complete documentation PDF.

**Markdown is the single source.** The HTML, PDF and DOCX under `out/` are all
generated — do not edit them by hand, or the next build silently discards the
change.

```bash
./build.sh              # both documents
./build.sh blog-post    # just one
```

| File | Purpose |
|---|---|
| `blog-post.md` | Introductory blog post — leads with the unsupported / non-production disclaimer |
| `documentation.md` | Complete reference: features, deployment, tenant setup, configuration, POC walkthrough |
| `release-notes.md` | Per-version capabilities, fixes and known issues, plus what is planned. No dates — versions, not calendars |
| `style.css` | Shared print/screen styling, so both documents look like one product |
| `assets/` | Diagrams and screenshots referenced by both documents |
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
  after conversion, since pandoc cannot express the distinction.
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

## Keeping them honest

These documents make specific claims about behaviour. When a claim stops being
true it becomes worse than useless, because a reader has no way to know. The
previous revision stated *"every authenticated administrator holds full rights;
no reviewer/read-only roles"* long after roles had shipped.

Re-check on any release that changes authorization, the access lifecycle, the
chat integrations, or the configuration surface.
