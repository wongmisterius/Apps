#!/usr/bin/env bash
# Builds LLVM 18.1.8 libc++/libc++abi (static) for the PS4 into libcxx18/ (docs/BUILDING.md §3).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/tools/env-build.sh"
SRC="$ROOT/deps/llvm-project"
if [ ! -d "$SRC" ]; then
    git clone -q --depth 1 --branch llvmorg-18.1.8 --filter=blob:none --sparse \
        https://github.com/llvm/llvm-project "$SRC"
    git -C "$SRC" sparse-checkout set runtimes libcxx libcxxabi libunwind cmake llvm/cmake llvm/utils/llvm-lit third-party
fi
# OpenOrbis has FreeBSD's sendfile, not Linux's.
sed -i 's|^#if __has_include(<sys/sendfile.h>)$|#if __has_include(<sys/sendfile.h>) \&\& !defined(__ORBIS__)|' \
    "$SRC/libcxx/src/filesystem/operations.cpp"
B="$ROOT/deps/build-libcxx"
cmake -S "$SRC/runtimes" -B "$B" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NXO_MIX/toolchain/runtimes-ps4.cmake" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PS4_LIBCXX" \
    -DLLVM_ENABLE_RUNTIMES="libcxx;libcxxabi" \
    -DLIBCXX_ENABLE_SHARED=OFF -DLIBCXXABI_ENABLE_SHARED=OFF \
    -DLIBCXX_HAS_MUSL_LIBC=ON -DLIBCXX_HAS_PTHREAD_API=ON -DLIBCXX_CXX_ABI=libcxxabi \
    -DLIBCXX_ENABLE_NEW_DELETE_DEFINITIONS=OFF -DLIBCXXABI_USE_LLVM_UNWINDER=OFF \
    -DLIBCXX_ENABLE_TIME_ZONE_DATABASE=OFF -DLIBCXX_INCLUDE_BENCHMARKS=OFF \
    -DLIBCXX_INCLUDE_TESTS=OFF -DLIBCXXABI_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_TESTS=OFF
cmake --build "$B"
cmake --install "$B"
CS="$PS4_LIBCXX/include/c++/v1/__config_site"
grep -q '^#undef __FreeBSD__' "$CS" || printf '\n#undef __FreeBSD__\n' >> "$CS"
ls -la "$PS4_LIBCXX/lib"
