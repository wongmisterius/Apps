# Building from source

## Linux (Ubuntu 24.04), the short way

All paths are now relative to the repository. On Linux the system LLVM 18 and the SDK's Linux
packaging tools are used:

```bash
sudo apt install clang-18 lld-18 llvm-18 ninja-build glslang-tools perl make python3 git curl
pip install "cmake>=3.31"                       # Eden needs CMake 3.31+
# PkgTool.Core (.NET Core 3) needs OpenSSL 1.1: Ubuntu 20.04's libssl1.1 .deb installs fine on 24.04
# SDK: orbis-sdk-v1.tar.gz unpacked as sdk-dl/orbis-sdk-v1/ (see §2, no local changes needed)
git clone --depth 1 --filter=blob:none --sparse https://github.com/OpenOrbis/OpenOrbis-PS4-Toolchain sdk-dl/PS4Toolchain
git -C sdk-dl/PS4Toolchain sparse-checkout set samples/piglet   # prx files for the package
bash scripts/build-libcxx.sh                    # -> libcxx18/
bash scripts/build-deps.sh                      # -> prefix/ (OpenSSL, FFmpeg)
bash scripts/fetch-eden.sh                      # -> deps/eden (+ patches/eden)
bash configure-eden.sh
source tools/env-build.sh && unset OO_PS4_TOOLCHAIN && cmake --build build-eden --target eden-ps4
bash package-eden.sh                            # -> out-eden/*.pkg, ELF copy in elf/
```

Everything is built with `-nostdlibinc`: on a Linux host clang otherwise adds the host's
`/usr/include` to the PS4 target, and `__has_include(<linux/...>)` picks up glibc headers. If GitHub
source archives are refused (some proxies only allow git), `fetch-eden.sh` and Eden's CPMUtil
(patch `0028`) fetch the same commits with git.

The rest of this page is the original Windows procedure.

## Windows

Everything was built on **Windows 11 with Git Bash**. It has only ever been built on one machine, and
the scripts use that machine's absolute paths. Expect to adjust things; this page lists every piece
and how it was made, so the build can be reproduced.

## 0. Paths

The scripts derive every path from the repository's location. On Windows set `NXO_TOOLS` to the
folder holding `llvm/bin`, `cmake/bin`, `ninja` and `glslang/bin`, and `NXO_OO` to the OpenOrbis
PS4Toolchain folder if it is not `sdk-dl/PS4Toolchain` (`tools/env-build.sh`).

## 1. Host tools (in your tools folder)

| Tool | Version used | Notes |
|---|---|---|
| LLVM / clang | 18.1.8 (Windows x64) | in `tools/llvm/bin` |
| CMake | 4.4.3 | in `tools/cmake-4.4.3-windows-x86_64/bin` |
| Ninja | any recent | in `tools/ninja` |
| glslang | any recent | in `tools/glslang/bin` (also copied to this repo's `tools/bin/glslangValidator.exe`) |
| OpenOrbis PS4 Toolchain | 0.5.4 | in `tools/OpenOrbis/OpenOrbis/PS4Toolchain`; only its `samples/piglet` modules are used by `package-eden.sh` |
| llvm-mingw | 20260922 ucrt | only as FFmpeg's host compiler |
| MSYS2 `make` and `perl` | from repo.msys2.org | extracted into this repo's `tools/usr` and `tools/msys-perl` (for OpenSSL) |
| Python 3 | any | packaging icon, font generation |

## 2. The PS4 SDK

Download `orbis-sdk-v1` from the
[orbis-porting-kit release](https://github.com/orbis-ports/orbis-porting-kit/releases/tag/orbis-sdk-v1)
and unpack it as `sdk-dl/orbis-sdk-v1/`. Two local changes were needed on Windows:

- copy the Windows binaries of OpenOrbis 0.5.4 (`create-fself.exe`, `create-gp4.exe`,
  `PkgTool.Core.exe` and its DLLs) into `sdk-dl/orbis-sdk-v1/sdk/bin/windows/`;
- in `orbis-compat/cmake/ps4-openorbis.cmake`, add a Windows branch next to the macOS one:
  `elseif(CMAKE_HOST_WIN32) set(OO_PS4_BINDIR "${OO_PS4_TOOLCHAIN}/bin/windows")`.

## 3. libc++ 18 for the PS4 (`libcxx18/`)

The SDK's libc++ 11 lacks what Eden needs (ranges, format, jthread/stop_token...).

- Source: llvm-project `llvmorg-18.1.8` (sparse checkout: `runtimes`, `libcxx`, `libcxxabi`,
  `libunwind`, `cmake`, `llvm/cmake`) in `deps/llvm-project`.
- Patch: `libcxx/src/filesystem/operations.cpp` line ~40, add `&& !defined(__ORBIS__)` to the Linux
  `sendfile` condition (OpenOrbis has FreeBSD's `sendfile`).
- Configure `deps/llvm-project/runtimes` with `-DCMAKE_TOOLCHAIN_FILE=toolchain/runtimes-ps4.cmake`,
  `-DLLVM_ENABLE_RUNTIMES="libcxx;libcxxabi"`, static only (`LIBCXX_ENABLE_SHARED=OFF`,
  `LIBCXXABI_ENABLE_SHARED=OFF`), `LIBCXX_HAS_MUSL_LIBC=ON`, `LIBCXX_CXX_ABI=libcxxabi`,
  `LIBCXX_ENABLE_NEW_DELETE_DEFINITIONS=OFF` (libc++abi provides them), `LIBCXXABI_USE_LLVM_UNWINDER=OFF`
  (the SDK's libunwind is used), `LIBCXX_ENABLE_TIME_ZONE_DATABASE=OFF`, `LIBCXX_INCLUDE_BENCHMARKS=OFF`,
  install prefix `libcxx18/`.
- After installing, append `#undef __FreeBSD__` to `libcxx18/include/c++/v1/__config_site` (as the
  SDK's libc++ 11 does).

## 4. OpenSSL 3.6.2 and FFmpeg (`prefix/`)

`source tools/env-build.sh` sets `PATH`, `PERL5LIB` and `PS4_CFLAGS`/`PS4_PREFIX` for C dependencies.

OpenSSL (`deps/openssl-3.6.2`), compiler flags in `CFLAGS` (Configure misreads `-isysroot X` as
arguments):

```bash
CFLAGS="$PS4_CFLAGS" CC=clang AR=llvm-ar RANLIB=llvm-ranlib perl Configure linux-x86_64-clang \
  no-shared no-tests no-apps no-docs no-dso no-async no-afalgeng no-engine no-ui-console \
  no-secure-memory no-module --with-rand-seed=devrandom --prefix=<repo>/prefix --libdir=lib \
  --openssldir=/app0/ssl
make -j16 build_libs && make install_dev
```

FFmpeg (commit `c7b5f1537d`, built in `deps/build-ffmpeg`, needs a local `TMPDIR`):

```bash
../ffmpeg/configure --prefix=<repo>/prefix --enable-cross-compile --arch=x86_64 --target-os=freebsd \
  --host-cc=<llvm-mingw>/bin/x86_64-w64-mingw32-clang --host-ld=<llvm-mingw>/bin/x86_64-w64-mingw32-clang \
  --cc='bash <repo>/tools/ps4-cc.sh' --ld='bash <repo>/tools/ps4-cc.sh' --ar=llvm-ar --ranlib=llvm-ranlib \
  --nm=llvm-nm --disable-x86asm --disable-autodetect --disable-everything --disable-programs --disable-doc \
  --disable-avdevice --disable-avformat --disable-network --disable-swresample \
  --enable-decoder='h264,vp8,vp9' --enable-filter='yadif,scale' --enable-pic --enable-pthreads \
  --disable-shared --enable-static
make -j16 && make install
```

## 5. Eden

```bash
bash scripts/fetch-eden.sh     # Eden 5f142c79 + patches/eden -> deps/eden
bash configure-eden.sh         # -> build-eden/ (first run ~10 min; CPM downloads Eden's bundled deps)
source tools/env-build.sh && unset OO_PS4_TOOLCHAIN
cmake --build build-eden --target eden-ps4
```

Notes:
- `frontend/` is injected into Eden's CMake project with `-DCMAKE_PROJECT_yuzu_INCLUDE=frontend/inject.cmake`
  (the way ProsperoEden adds its frontend).
- Never pass `-DCMAKE_CXX_FLAGS` on the command line: it replaces the PS4 flags from the toolchain.
  After changing `toolchain/eden-ps4.cmake` flags, reconfigure with
  `cmake -U CMAKE_C_FLAGS -U CMAKE_CXX_FLAGS build-eden`.
- The whole of Eden is built with `-femulated-tls`, `-march=btver2` and frame pointers.

## 6. Package

```bash
bash package-eden.sh   # -> out-eden/IV0000-EDPS00001_00-EDENPS4000000000.pkg, ELF copy in elf/
```

The package is laid out and signed like OpenOrbis's own samples (paid `0x3800000000000011`, default
authinfo, `sce_module/libc.prx` + `libSceFios2.prx`, `sce_sys/about/right.sprx`, SFO category `gd`).
That is the only layout that, on the test console, both starts and gets the full ~4.4 GiB of direct
memory; see `TECHNICAL.md`. Keep the ELF of every package you install: crash reports give
`eboot+0x...` offsets that only that exact ELF can symbolize.

## Other folders

- `probe/`: eden-probe, the small test program used before porting Eden to measure the console
  (package layouts, memory limits, JIT, double mapping, exception handling, GPU).
- `tests/`: host-side tests (texture-cache page ownership and retirement, boost flat_map) and the
  startup checks that also run on the console.
- `toolchain/shaders/`: the compute shader of the component-mapping self-test.
