#!/bin/sh
# Rebuilds ui/symbols/MaterialSymbols.ttf: the Material Symbols the app draws,
# and no others (stratus-app#195). The whole font is ten megabytes; the glyphs
# named in ui/symbols/names.txt are a few kilobytes. Add a name there, run
# this, and give Glyph.kt the codepoint it prints.
#
# Outlined, unfilled, weight 400, optical size 24: the variable axes are fixed
# here rather than in the app, since a static instance is what can be subset.
set -eu

# Pinned, so the same names make the same font.
COMMIT=10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7
FONT='MaterialSymbolsOutlined[FILL,GRAD,opsz,wght]'
BASE="https://raw.githubusercontent.com/google/material-design-icons/$COMMIT/variablefont"

HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsSL -o "$WORK/full.ttf" "$BASE/$(printf %s "$FONT" | sed 's/\[/%5B/; s/\]/%5D/').ttf"
curl -fsSL -o "$WORK/codepoints" "$BASE/$(printf %s "$FONT" | sed 's/\[/%5B/; s/\]/%5D/').codepoints"

unicodes=""
while read -r name; do
	[ -n "$name" ] || continue
	cp="$(awk -v n="$name" '$1 == n { print $2 }' "$WORK/codepoints")"
	[ -n "$cp" ] || { echo "no such symbol: $name" >&2; exit 1; }
	printf '%-16s \\u%s\n' "$name" "$(echo "$cp" | tr a-z A-Z)"
	unicodes="$unicodes${unicodes:+,}U+$cp"
done < "$HERE/ui/symbols/names.txt"

docker run --rm -v "$WORK":/w -w /w python:3.13-slim sh -c "
	pip install -q --root-user-action=ignore fonttools==4.60.1 &&
	fonttools varLib.instancer -q full.ttf FILL=0 GRAD=0 opsz=24 wght=400 -o static.ttf &&
	pyftsubset static.ttf --unicodes='$unicodes' --no-layout-closure --layout-features='' --output-file=subset.ttf"
cp "$WORK/subset.ttf" "$HERE/ui/symbols/MaterialSymbols.ttf"
