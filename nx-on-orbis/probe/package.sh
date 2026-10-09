#!/usr/bin/env bash
# Round 3 packages into ../out/:
#   E  EDNP00005  eden-probe signed and laid out like OpenOrbis's own hello_world sample
#                 (paid 0x...11, default authinfo, sce_module libc/Fios2, right.sprx, SFO gd)
#   B  EDNP00002  eden-probe signed and laid out exactly like the SoH package (paid 0x...35 + Piglet authinfo)
# Round 2 showed orbis-ports' own layout (no sce_module, no right.sprx) does not load on this console,
# and that the SoH layout loads but gives the process only 768 MiB of direct memory.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
SDK="$ROOT/sdk-dl/orbis-sdk-v1/sdk"
OO="${NXO_OO:-$ROOT/sdk-dl/PS4Toolchain}"   # OpenOrbis PS4Toolchain checkout (samples/piglet)
export DOTNET_ROLL_FORWARD=LatestMajor
export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1   # PkgTool.Core on hosts without ICU
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) BIN="$SDK/bin/windows"; X=.exe; mp() { cygpath -m "$1"; } ;;
  *)                    BIN="$SDK/bin/linux";   X=;     mp() { printf '%s' "$1"; } ;;
esac
PY="$(command -v python3 || command -v python)"
export OO_PS4_TOOLCHAIN="$(mp "$SDK")"
OUT="$ROOT/out"
rm -rf "$OUT"; mkdir -p "$OUT"
# System auth info + program id of the OpenOrbis Piglet sample: what SoH ships with.
SOH_AUTHINFO="000000000000000000000000001C004000FF000000000080000000000000000000000000000000000000008000400040000000000000008000000000000000080040FFFF000000F000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

# pkg <letter> <title id> <label16> <title> <elf> <oo|soh> [variant]
pkg() {
    local L="$1" TID="$2" LABEL="$3" TITLE="$4" IN="$5" SIGN="$6" VAR="$7"
    local CID="IV0000-${TID}_00-${LABEL}" ST="$ROOT/stage-$L"
    rm -rf "$ST"; mkdir -p "$ST/sce_sys/about" "$ST/sce_module"
    "$PY" "$HERE/make-icon.py" "$ST/sce_sys/icon0.png" "$L"
    if [ "$SIGN" = soh ]; then
        "$BIN/create-fself$X" -in="$(mp "$IN")" -out="$(mp "$ST/x.oelf")" \
            --eboot "$(mp "$ST/eboot.bin")" --paid 0x3800000000000035 --authinfo "$SOH_AUTHINFO" >/dev/null
    else
        "$BIN/create-fself$X" -in="$(mp "$IN")" -out="$(mp "$ST/x.oelf")" \
            --eboot "$(mp "$ST/eboot.bin")" --paid 0x3800000000000011 >/dev/null
    fi
    rm -f "$ST/x.oelf"
    cp "$OO/samples/piglet/sce_sys/about/right.sprx" "$ST/sce_sys/about/"
    cp "$OO/samples/piglet/sce_module/libc.prx" "$OO/samples/piglet/sce_module/libSceFios2.prx" "$ST/sce_module/"
    local FILES="eboot.bin sce_sys/param.sfo sce_sys/icon0.png sce_sys/about/right.sprx sce_module/libc.prx sce_module/libSceFios2.prx"
    if [ -n "$VAR" ]; then echo "$VAR" > "$ST/variant.txt"; FILES="$FILES variant.txt"; fi
    (
        cd "$ST"
        local P="$BIN/PkgTool.Core$X" SFO=sce_sys/param.sfo
        "$P" sfo_new $SFO >/dev/null
        s() { "$P" sfo_setentry $SFO "$1" --type "$2" --maxsize "$3" --value "$4" >/dev/null; }
        s APP_TYPE Integer 4 1
        s APP_VER Utf8 8 "01.00"
        s ATTRIBUTE Integer 4 0
        if [ "$SIGN" = soh ]; then
            s CATEGORY Utf8 4 gde; s FORMAT Utf8 4 obs; s SYSTEM_VER Integer 4 1020
        else
            s CATEGORY Utf8 4 gd; s SYSTEM_VER Integer 4 0
        fi
        s CONTENT_ID Utf8 48 "$CID"
        s DOWNLOAD_DATA_SIZE Integer 4 0
        s TITLE Utf8 128 "$TITLE"
        s TITLE_ID Utf8 12 "$TID"
        s VERSION Utf8 8 "01.00"
        "$BIN/create-gp4$X" -out pkg.gp4 --content-id="$CID" --files "$FILES" >/dev/null
        "$P" pkg_build pkg.gp4 "$(mp "$OUT")" >/dev/null
    )
    echo "$L: $CID.pkg"
}

pkg E EDNP00005 EDENPROBEE000000 "Eden Probe E" "$ROOT/build-probe/eden_probe" oo  E3
pkg B EDNP00002 EDENPROBEB000000 "Eden Probe B" "$ROOT/build-probe/eden_probe" soh B3
ls -la "$OUT"
