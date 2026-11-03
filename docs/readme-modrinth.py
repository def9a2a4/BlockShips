#!/usr/bin/env python3
"""Print README.md with repo-relative links rewritten to absolute GitHub URLs.

For pasting into the Modrinth (or Spigot) project description. Modrinth resolves neither
relative paths nor GitHub heading anchors, so a verbatim paste renders every image broken.

The README itself stays relative: that is what renders correctly on GitHub, and one source of
truth beats a second copy that drifts.

Usage: docs/readme-modrinth.py <ref>     (or: make readme-modrinth README_REF=v0.0.18)

<ref> is a tag or branch, and it is required on purpose. docs/flight-model.md does not exist on
main, so defaulting to main would silently emit a dead link.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = "def9a2a4/BlockShips"


def rewrite(markdown: str, ref: str, repo: str = REPO) -> str:
    """Absolutise every repo-relative link in `markdown`.

    Rules are keyed on the KIND of link, not on the file extension, and all are global — two
    lines in the README carry two links each, so a non-global substitution would leave the
    second one relative and ship a broken image.
    """
    raw = f"https://raw.githubusercontent.com/{repo}/{ref}"
    blob = f"https://github.com/{repo}/blob/{ref}"

    # Images need the bytes, so they point at raw. Done first so the link rule below only sees
    # what is left: that ordering is what keeps `[![gif](x.gif)](x.webm)` correct, because raw
    # serves .webm as an attachment and a raw link would download the video instead of playing it.
    markdown = re.sub(r"!\[([^\]]*)\]\(docs/", rf"![\1]({raw}/docs/", markdown)

    # Everything still relative is a link, not an image, and belongs on the rendered blob page.
    markdown = re.sub(r"\]\(docs/", rf"]({blob}/docs/", markdown)

    # Anchors become absolute rather than being stripped to plain text, which would throw away
    # the reader's only path to the config documentation.
    markdown = re.sub(r"\]\(#([A-Za-z0-9_-]+)\)", rf"]({blob}/README.md#\1)", markdown)

    return markdown


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "ref",
        help="tag or branch the links should point at, e.g. v0.0.18. No default: main is "
        "missing files that exist on release branches, so a default would emit dead links.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if any relative link or anchor survived, instead of printing",
    )
    args = parser.parse_args()

    # `make readme-modrinth` with README_REF unset passes an empty string, which argparse happily
    # accepts and which would silently produce .../blob//docs/... — reject it here, not in Make.
    if not args.ref.strip():
        parser.error("ref is empty. Pass a tag or branch, e.g. make readme-modrinth README_REF=v0.0.18")

    readme = Path(__file__).resolve().parent.parent / "README.md"
    out = rewrite(readme.read_text(encoding="utf-8"), args.ref)

    if args.check:
        leftovers = [
            f"{n}: {line}"
            for n, line in enumerate(out.splitlines(), 1)
            if "](docs/" in line or re.search(r"\]\(#", line)
        ]
        if leftovers:
            print("relative links survived the rewrite:", file=sys.stderr)
            print("\n".join(leftovers), file=sys.stderr)
            return 1
        print(f"ok: no relative links or anchors left ({readme})")
        return 0

    sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
