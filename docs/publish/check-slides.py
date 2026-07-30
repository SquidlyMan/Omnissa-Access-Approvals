#!/usr/bin/env python3
"""Report where the PowerPoint decks have fallen out of step. Changes nothing.

    ./check-slides.py                 # report
    ./check-slides.py --fingerprint   # re-record the formatting baseline

The decks live outside the repository, alongside the other hand-held
deliverables, so they are not committed here. What IS committed is
slides-fingerprint.json: the geometry, palette, fonts and type sizes of every
slide, reduced to a few kilobytes of text. That gives the formatting an
enforceable baseline without carrying ten megabytes of binary in git.

This tool is deliberately read-only. The decks are the one deliverable whose
design cannot be regenerated -- pandoc's pptx writer emits placeholder slides
and would flatten it -- so they are edited by hand in PowerPoint, and the build
has no business rewriting them. It reports; a human decides.

The reason a report is worth having at all: no text box in either deck has
autofit set. Text that outgrows its box runs off the card silently, showing up
for the first time in front of an audience.
"""

import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent          # docs/publish
REPO = HERE.parent.parent                       # repository root
DECKS = REPO.parent                             # the deliverables folder
FINGERPRINT = HERE / "slides-fingerprint.json"

DECK_FILES = [
    "Access-Approval-Tool-for-Omnissa-Full-Deck.pptx",
    "Access-Approval-Tool-Summary-1Pager.pptx",
]

EMU = 914400
SLIDE_RE = re.compile(r"ppt/slides/slide(\d+)\.xml$")

findings = []


def note(deck, msg):
    findings.append(f"{deck}: {msg}")


# ---------------------------------------------------------------- deck reading


def slides(zf):
    """Slide parts in slide order, not zip order."""
    out = []
    for name in zf.namelist():
        m = SLIDE_RE.match(name)
        if m:
            out.append((int(m.group(1)), name))
    return [(n, zf.read(p).decode("utf-8", "replace")) for n, p in sorted(out)]


def runs(xml):
    return re.findall(r"<a:t>([^<]*)</a:t>", xml)


def text(xml):
    """Slide text with the XML entities turned back into characters."""
    joined = " ".join(runs(xml))
    for entity, char in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                         ("&quot;", '"'), ("&apos;", "'")):
        joined = joined.replace(entity, char)
    return " ".join(joined.split())


def shapes(xml):
    """Every shape with its geometry and type, in document order."""
    out = []
    for m in re.finditer(r"<p:(sp|pic|graphicFrame)>(.*?)</p:\1>", xml, re.S):
        kind, body = m.group(1), m.group(2)
        off = re.search(r'<a:off x="(-?\d+)" y="(-?\d+)"', body)
        ext = re.search(r'<a:ext cx="(\d+)" cy="(\d+)"', body)
        out.append({
            "kind": kind,
            "x": int(off.group(1)) if off else None,
            "y": int(off.group(2)) if off else None,
            "cx": int(ext.group(1)) if ext else None,
            "cy": int(ext.group(2)) if ext else None,
            "geom": (re.search(r'<a:prstGeom prst="([^"]+)"', body) or [None, None])[1]
                    if re.search(r'<a:prstGeom prst="([^"]+)"', body) else None,
            "colors": sorted(set(re.findall(r'srgbClr val="([0-9A-Fa-f]{6})"', body))),
            "fonts": sorted(set(re.findall(r'typeface="([^"]+)"', body))),
            "sizes": sorted(set(int(s) for s in re.findall(r'sz="(\d+)"', body))),
            "chars": len(text(body)),
            "body": body,
        })
    return out


# ------------------------------------------------------------ formatting check


def fingerprint_of(zf):
    """Everything about the look, and nothing about the words.

    `chars` is excluded on purpose: prose is expected to change, geometry and
    palette are not. Including it would make every wording fix look like a
    formatting break and the check would be ignored within a week.
    """
    out = {}
    for num, xml in slides(zf):
        sh = shapes(xml)
        shape_keys = [
            {k: s[k] for k in ("kind", "x", "y", "cx", "cy", "geom",
                               "colors", "fonts", "sizes")}
            for s in sh
        ]
        bg = re.search(r"<p:bg>.*?srgbClr val=\"([0-9A-Fa-f]{6})\"", xml, re.S)
        # The shape-tree transform, which per-shape geometry does not cover: it
        # offsets and scales every shape on the slide at once, so a change here
        # moves the whole design while each shape's own offset stays put. Found
        # by mutating it and watching this check stay silent.
        tree = re.search(r"<p:grpSpPr>(.*?)</p:grpSpPr>", xml, re.S)
        blob = json.dumps({"bg": bg.group(1) if bg else None,
                           "tree": re.sub(r"\s+", "", tree.group(1)) if tree else None,
                           "shapes": shape_keys}, sort_keys=True)
        out[str(num)] = {
            "format": hashlib.sha256(blob.encode()).hexdigest()[:16],
            # Per-shape character counts, recorded separately from the format
            # hash so that a wording change does not read as a formatting break
            # -- and so overflow can be judged against what demonstrably fits
            # today rather than against an estimate of font metrics.
            "chars": [s["chars"] for s in sh],
        }
    return out


def check_formatting(deck, zf, stored):
    current = fingerprint_of(zf)
    if stored is None:
        note(deck, f"no formatting baseline recorded yet "
                   f"({len(current)} slides) — run --fingerprint to set one")
        return
    gone = sorted(set(stored) - set(current), key=int)
    new = sorted(set(current) - set(stored), key=int)
    moved = sorted((s for s in set(stored) & set(current)
                    if stored[s]["format"] != current[s]["format"]), key=int)
    if gone:
        note(deck, f"slides removed since the baseline: {', '.join(gone)}")
    if new:
        note(deck, f"slides added since the baseline: {', '.join(new)}")
    if moved:
        note(deck, "FORMATTING CHANGED on slide(s) "
                   f"{', '.join(moved)} — geometry, colour, font or type size "
                   "differs from the recorded baseline")


# --------------------------------------------------------------- other checks


def project_version():
    pom = (REPO / "pom.xml").read_text(encoding="utf-8")
    return re.search(r"<version>([0-9][^<]*)</version>", pom).group(1)


FOOTER = "Access Approval Tool for Omnissa"


def check_version(deck, zf, version):
    """The running footer must name the release being shipped.

    Only the footer. The body of the deck cites earlier versions constantly and
    correctly -- "resolved in 1.16.1", "[1.5.0]" -- and an earlier draft of this
    check flagged all nine of them. A check that cries wolf about correct
    content is worse than no check, because it trains you to skip the output.
    """
    stale = []
    for num, xml in slides(zf):
        for m in re.finditer(re.escape(FOOTER) + r"\s*•\s*v(\d+\.\d+(?:\.\d+)?)",
                             text(xml)):
            if m.group(1) != version:
                stale.append(f"slide {num} says v{m.group(1)}")
    if stale:
        note(deck, f"footer names the wrong version ({len(stale)} slide(s), "
                   f"pom.xml says {version}): {', '.join(stale[:4])}"
                   f"{' …' if len(stale) > 4 else ''}")


def released_versions():
    """Every version with a CHANGELOG entry, plus the one being built."""
    out = set()
    changelog = REPO / "CHANGELOG.md"
    if changelog.is_file():
        out |= set(re.findall(r"^## \[(\d+\.\d+(?:\.\d+)?)\]",
                              changelog.read_text(encoding="utf-8"), re.M))
    return out


def check_version_citations(deck, zf, version):
    """Every version the deck names must be a release that exists.

    Distinct from the footer check, and worth having separately: the body cites
    earlier versions constantly and correctly -- "resolved in 1.16.1", "[1.5.0]"
    -- so comparing them to the current release would flag nine correct
    sentences. What is always wrong is citing a version that never shipped,
    which is what a typo or a renumbered release looks like.
    """
    known = released_versions()
    if not known:
        return
    known.add(version)

    # Only the forms that denote a release of THIS tool. A bare number cannot be
    # used: these decks also say "Java 17", "Spring Boot 4.1", "Vite 8" and
    # "OAuth 2.0", and matching loosely flagged OAuth's version number as a
    # missing release. The negative lookbehind matters too -- without it,
    # "V1.19.1" is matched from the wrong offset and reads as "19.1".
    CITED = re.compile(r"(?<![\w.])v(\d+\.\d+(?:\.\d+)?)(?![\w.])"     # v1.19.4
                       r"|\[(\d+\.\d+(?:\.\d+)?)\]"                    # [1.19.3]
                       r"|\bin (\d+\.\d+(?:\.\d+)?)(?![\w.])",         # in 1.19.2
                       re.I)

    unknown = []
    for num, xml in slides(zf):
        for m in CITED.finditer(text(xml)):
            cited = next(g for g in m.groups() if g)
            if cited in known:
                continue
            # A bare "1.19" is how the container tag is written, and matches any
            # patch on that line; treat it as real if any release starts with it.
            if cited.count(".") == 1 and any(k.startswith(cited + ".")
                                             for k in known):
                continue
            unknown.append(f"{cited} (slide {num})")
    if unknown:
        seen, ordered = set(), []
        for u in unknown:
            if u not in seen:
                seen.add(u)
                ordered.append(u)
        note(deck, "names version(s) with no CHANGELOG entry — a typo or a "
                   f"release that never shipped: {', '.join(ordered[:6])}"
                   f"{' …' if len(ordered) > 6 else ''}")


# There is deliberately no screenshot-freshness check. Matching deck images
# against docs/images by content hash was tried and reported 87 of 90 images as
# unmatched: the deck's copies are cropped and rescaled for a 16:9 card, so
# their bytes never equal the source. Perceptual comparison would need a real
# image library and would still be a guess. If a screenshot in the deck goes
# stale, the fix is to notice it while looking at the slide, not to be told
# something untrue by a script.


def check_env_vars(deck, zf):
    """Configuration slides must not name a variable the code does not have."""
    props = (REPO / "src" / "main" / "resources" / "application.properties")
    known = set()
    if props.is_file():
        body = props.read_text(encoding="utf-8")
        for m in re.finditer(r"\$\{([A-Z0-9_]+)", body):
            known.add(m.group(1))
    for folder in (REPO / "docs",):
        for p in folder.rglob("*.md"):
            for m in re.finditer(r"\b([A-Z][A-Z0-9]*(?:_[A-Z0-9]+){2,})\b",
                                 p.read_text(encoding="utf-8")):
                known.add(m.group(1))
    if not known:
        return

    unknown = set()
    for num, xml in slides(zf):
        for m in re.finditer(r"\b([A-Z][A-Z0-9]*(?:_[A-Z0-9]+){2,})\b", text(xml)):
            name = m.group(1)
            if name not in known:
                unknown.add(f"{name} (slide {num})")
    if unknown:
        note(deck, "names configuration variable(s) that appear nowhere in the "
                   f"code or docs: {', '.join(sorted(unknown))}")


def check_growth(deck, zf, stored, current):
    """Text that has grown materially beyond the amount known to fit.

    Neither deck sets autofit -- no normAutofit, no spAutoFit -- so PowerPoint
    lets text spill outside its shape silently, and it surfaces for the first
    time in front of an audience.

    Estimating capacity from font metrics was the obvious approach and it was
    wrong: calibrating a glyph width to make the current decks report clean is
    fitting the constant to the answer, and it flagged six boxes that plainly
    render correctly today. What the box holds today is not a guess -- it is
    observable. So the baseline records it, and this reports growth past it.
    Nothing is flagged until somebody makes a box's text longer.
    """
    if not stored:
        return
    GROWTH = 1.10   # a tenth more text than fits today is worth a look
    grown = []
    for slide in sorted(set(stored) & set(current), key=int):
        was, now = stored[slide].get("chars", []), current[slide]["chars"]
        if len(was) != len(now):
            continue        # shape added or removed; the formatting check says so
        for i, (before, after) in enumerate(zip(was, now)):
            if before and after > before * GROWTH:
                grown.append(f"slide {slide} (a text box grew from {before} to "
                             f"{after} characters)")
    if grown:
        note(deck, "text has outgrown what the box is known to hold, and there "
                   f"is no autofit to absorb it: {'; '.join(grown[:5])}"
                   f"{' …' if len(grown) > 5 else ''}")


def check_whats_coming(deck, zf, version):
    """The deck must not promise something the release notes call shipped."""
    notes = HERE / "release-notes.md"
    if not notes.is_file():
        return
    body = notes.read_text(encoding="utf-8")
    m = re.search(r"^## Recently shipped from this list\n(.*?)(?=^## |\Z)",
                  body, re.S | re.M)
    if not m:
        return

    # The bolded lead-in of each shipped bullet is its subject.
    shipped = re.findall(r"^- \*\*(.+?)\.?\*\*", m.group(1), re.M)
    deck_text = " ".join(text(x) for _, x in slides(zf)).lower()
    for subject in shipped:
        words = [w for w in re.findall(r"[a-z]{5,}", subject.lower())][:3]
        if not words:
            continue
        if all(w in deck_text for w in words):
            # Present is expected -- the deck says these shipped too. Only
            # complain if the deck frames it as still to come.
            if "shipped" not in deck_text and "already" not in deck_text:
                note(deck, f"release notes list \"{subject}\" as shipped, but "
                           "the deck does not say so anywhere")


# ---------------------------------------------------------------------- driver


def main():
    global DECKS
    record = "--fingerprint" in sys.argv
    if "--decks" in sys.argv:
        # Somewhere else to point it: used to prove the checks fire against a
        # deliberately broken copy, since a checker nobody has seen fail is
        # indistinguishable from one that cannot.
        DECKS = Path(sys.argv[sys.argv.index("--decks") + 1]).resolve()
    stored = {}
    if FINGERPRINT.is_file():
        stored = json.loads(FINGERPRINT.read_text(encoding="utf-8"))

    version = project_version()
    print(f"    project version {version}")
    print(f"    decks in {DECKS}")

    fresh = {}
    present = 0
    for filename in DECK_FILES:
        path = DECKS / filename
        if not path.is_file():
            print(f"    - {filename}: not found, skipped")
            continue
        present += 1
        with zipfile.ZipFile(path) as zf:
            n = len(slides(zf))
            print(f"    - {filename}: {n} slide(s)")
            fresh[filename] = fingerprint_of(zf)
            if not record:
                check_formatting(filename, zf, stored.get(filename))
                check_version(filename, zf, version)
                check_version_citations(filename, zf, version)
                check_env_vars(filename, zf)
                check_growth(filename, zf, stored.get(filename),
                             fresh[filename])
                check_whats_coming(filename, zf, version)

    if record:
        FINGERPRINT.write_text(json.dumps(fresh, indent=2, sort_keys=True) + "\n",
                               encoding="utf-8")
        print(f"    recorded formatting baseline for {len(fresh)} deck(s) "
              f"in {FINGERPRINT.name}")
        return 0

    if not present:
        print("    no decks found — nothing to check")
        return 0

    if not findings:
        print("    decks are in step with the repository")
        return 0

    print()
    for f in findings:
        print(f"    DRIFT  {f}")
    print()
    print("    Nothing was changed. These are edited by hand in PowerPoint;")
    print("    the design cannot be regenerated, so the build never rewrites them.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
