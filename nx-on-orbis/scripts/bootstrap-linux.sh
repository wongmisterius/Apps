#!/usr/bin/env bash
# One-shot setup of a fresh Ubuntu 24.04 container for building nx-on-orbis (docs/BUILDING.md):
# host tools, the orbis SDK, OpenOrbis' piglet prx files, libc++ 18, OpenSSL/FFmpeg and Eden.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export DEBIAN_FRONTEND=noninteractive
apt-get install -y -q glslang-tools rar >/dev/null 2>&1 || { apt-get update -q >/dev/null; apt-get install -y -q glslang-tools rar >/dev/null; }
command -v cmake >/dev/null && cmake --version | grep -q "version [4-9]" || pip install --break-system-packages -q "cmake==4.1.2"
if ! ldconfig -p | grep -q "libssl.so.1.1"; then   # PkgTool.Core (.NET Core 3) needs OpenSSL 1.1
    d="$(mktemp -d)"; curl -sSfL -o "$d/l.deb" http://archive.ubuntu.com/ubuntu/pool/main/o/openssl/libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb
    dpkg -i "$d/l.deb" >/dev/null
fi
mkdir -p sdk-dl
if [ ! -f sdk-dl/orbis-sdk-v1/BUNDLE.txt ]; then
    curl -sSL --fail -o sdk-dl/orbis-sdk-v1.tar.gz https://github.com/orbis-ports/orbis-porting-kit/releases/download/orbis-sdk-v1/orbis-sdk-v1.tar.gz
    echo "f4f079a4df334b46a76996f895283904283d6a006f97102aafaa53d4ecfc9622  sdk-dl/orbis-sdk-v1.tar.gz" | sha256sum -c -
    tar -xzf sdk-dl/orbis-sdk-v1.tar.gz -C sdk-dl
fi
if [ ! -f sdk-dl/PS4Toolchain/samples/piglet/sce_module/libc.prx ]; then
    git clone -q --depth 1 --filter=blob:none --sparse https://github.com/OpenOrbis/OpenOrbis-PS4-Toolchain sdk-dl/PS4Toolchain
    git -C sdk-dl/PS4Toolchain sparse-checkout set samples/piglet
fi
[ -f libcxx18/lib/libc++.a ] || bash scripts/build-libcxx.sh > /dev/null
[ -f prefix/lib/libcrypto.a ] && [ -f prefix/lib/libavcodec.a ] || bash scripts/build-deps.sh > /dev/null
[ -d deps/eden ] || bash scripts/fetch-eden.sh
echo "bootstrap done"
