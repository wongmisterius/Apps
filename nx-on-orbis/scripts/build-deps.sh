#!/usr/bin/env bash
# Builds OpenSSL 3.6.2 and FFmpeg (h264/vp8/vp9) for the PS4 into prefix/ (docs/BUILDING.md §4).
#   bash scripts/build-deps.sh [openssl] [ffmpeg]     (default: both)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/tools/env-build.sh"
D="$ROOT/deps"; mkdir -p "$D" "$ROOT/prefix"
J="${JOBS:-$(nproc)}"
what="${*:-openssl ffmpeg}"

if [[ " $what " == *" openssl "* ]]; then
    [ -d "$D/openssl-3.6.2" ] || { curl -sSL --fail https://github.com/openssl/openssl/releases/download/openssl-3.6.2/openssl-3.6.2.tar.gz | tar -xz -C "$D"; }
    (
        cd "$D/openssl-3.6.2"
        # Compiler flags go in CFLAGS: Configure misreads "-isysroot X" given as arguments.
        CFLAGS="$PS4_CFLAGS" CC=clang AR=llvm-ar RANLIB=llvm-ranlib perl Configure linux-x86_64-clang \
            no-shared no-tests no-apps no-docs no-dso no-async no-afalgeng no-engine no-ui-console \
            no-secure-memory no-module --with-rand-seed=devrandom --prefix="$PS4_PREFIX" --libdir=lib \
            --openssldir=/app0/ssl
        make -j"$J" build_libs && make install_dev
    )
fi

if [[ " $what " == *" ffmpeg "* ]]; then
    if [ ! -d "$D/ffmpeg" ]; then
        git init -q "$D/ffmpeg"
        git -C "$D/ffmpeg" fetch -q --depth 1 https://github.com/FFmpeg/FFmpeg c7b5f1537d9c52efa50fd10d106ca015ddde1818
        git -C "$D/ffmpeg" checkout -q FETCH_HEAD
    fi
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) HOSTCC="${NXO_HOSTCC:?set NXO_HOSTCC to llvm-mingw clang}" ;;
        *) HOSTCC="${NXO_HOSTCC:-cc}" ;;
    esac
    mkdir -p "$D/build-ffmpeg/tmp"
    (
        cd "$D/build-ffmpeg"
        export TMPDIR="$PWD/tmp"
        ../ffmpeg/configure --prefix="$PS4_PREFIX" --enable-cross-compile --arch=x86_64 --target-os=freebsd \
            --host-cc="$HOSTCC" --host-ld="$HOSTCC" --cc="bash $NXO_MIX/tools/ps4-cc.sh" --ld="bash $NXO_MIX/tools/ps4-cc.sh" \
            --ar=llvm-ar --ranlib=llvm-ranlib --nm=llvm-nm --disable-x86asm --disable-autodetect \
            --disable-everything --disable-programs --disable-doc --disable-avdevice --disable-avformat \
            --disable-network --disable-swresample --enable-decoder='h264,vp8,vp9' --enable-filter='yadif,scale' \
            --enable-pic --enable-pthreads --disable-shared --enable-static
        make -j"$J" && make install
    )
fi
ls -la "$PS4_PREFIX/lib"
