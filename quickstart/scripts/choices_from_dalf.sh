#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <dalf-path> <entity-filter>" >&2
  exit 1
fi

DALF="$1"
ENTITY_FILTER="$2"
PKGID="$(basename "$DALF" .dalf)"
OUT="/tmp/daml-codegen/${PKGID}"
TMP="/tmp/min-dar-${PKGID}"
DAR="/tmp/${PKGID}.min.dar"

rm -rf "$OUT" "$TMP" "$DAR"
mkdir -p "$OUT" "$TMP/dalf" "$TMP/META-INF"
cp "$DALF" "$TMP/dalf/${PKGID}.dalf"
cat > "$TMP/META-INF/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Main-Dalf: dalf/${PKGID}.dalf
EOF
( cd "$TMP" && zip -qr "$DAR" . )

echo "== codegen (DAR) =="
if ! daml codegen java -o "$OUT" "$DAR"; then
  echo "codegen failed (likely missing dependent DALFs). DAR=$DAR" >&2
  exit 2
fi

echo "== choices for entity filter '${ENTITY_FILTER}' =="
found=0
grep -RIl "class ${ENTITY_FILTER}\b" "$OUT" | while read -r cls; do
  found=1
  echo "-- ${cls#$OUT/}"
  grep -RIn "exercise" "$cls" | sed 's/^/   /'
done
[ $found -eq 0 ] && echo "No classes matched ${ENTITY_FILTER} in codegen output."

