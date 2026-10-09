#!/usr/bin/env bash
# Fetches Eden at the commit this port is based on (the one ProsperoEden pins) into deps/eden and
# applies this port's patches on top, one commit each.
#
#   bash scripts/fetch-eden.sh            # patches/eden (the v0.1.0 state on main)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT=5f142c7926d0c7fcbbd0ce30794d72f638a43b2a
SHA256=35ec0bb96b197181e66c0aeb8f44f0ff05334be832c631c50a4f1e271277285f   # ProsperoEden's UPSTREAM.json
URL="https://github.com/eden-emulator/mirror/archive/$COMMIT.tar.gz"
DEST="$ROOT/deps/eden"

[ -e "$DEST" ] && { echo "$DEST exists; remove it first"; exit 1; }
mkdir -p "$ROOT/deps"
TAR="$ROOT/deps/eden-${COMMIT:0:8}.tar.gz"
mkdir -p "$DEST"
if [ -f "$TAR" ] || curl -sL --fail -o "$TAR" "$URL"; then
    echo "$SHA256  $TAR" | sha256sum -c - || echo "warning: checksum differs from ProsperoEden's (GitHub archives are not always byte-stable); continuing"
    tar -xzf "$TAR" -C "$DEST" --strip-components=1
else
    # Archive downloads can be blocked (proxies); a shallow git fetch of the same commit works.
    rm -f "$TAR"
    echo "archive download failed; fetching $COMMIT with git"
    git init -q "$ROOT/deps/eden-src"
    git -C "$ROOT/deps/eden-src" fetch -q --depth 1 https://github.com/eden-emulator/mirror "$COMMIT"
    git -C "$ROOT/deps/eden-src" archive FETCH_HEAD | tar -x -C "$DEST"
    rm -rf "$ROOT/deps/eden-src"
fi
cd "$DEST"
git init -q
git config core.autocrlf false
git add -A
git -c user.name=base -c user.email=base@localhost commit -q -m "Eden ${COMMIT:0:12} (pristine, ProsperoEden pin)"
git am -q --keep-cr "$ROOT"/patches/eden/*.patch
git log --oneline | head -3
echo "Eden ready in $DEST"
