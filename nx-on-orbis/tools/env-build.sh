# source this: environment for building the PS4 C/C++ dependencies and Eden.
# Paths are derived from this file's location; works on Linux and on Windows (Git Bash).
#   NXO_TOOLS  optional folder with host tools (llvm/bin, cmake, ninja, glslang); on Linux the
#              system clang-18 / lld / cmake / ninja / glslangValidator are used when unset.
#   NXO_OO     OpenOrbis PS4Toolchain 0.5.4 checkout (for samples/piglet prx files when packaging);
#              default: $NXO_ROOT/sdk-dl/PS4Toolchain
NXO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export NXO_ROOT
export NXO_OO="${NXO_OO:-$NXO_ROOT/sdk-dl/PS4Toolchain}"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    NXO_MIX="$(cygpath -m "$NXO_ROOT")"
    P="$NXO_ROOT/tools/msys-perl/usr"
    export PERL5LIB=$P/share/perl5/core_perl:$P/lib/perl5/core_perl
    T="${NXO_TOOLS:?set NXO_TOOLS to your Windows tools folder}"
    export PATH="$NXO_ROOT/tools/bin:$P/bin:$NXO_ROOT/tools/usr/bin:$T/llvm/bin:$T/cmake/bin:$T/ninja:$T/glslang/bin:$PATH"
    ;;
  *)
    NXO_MIX="$NXO_ROOT"
    [ -n "${NXO_TOOLS:-}" ] && export PATH="$NXO_TOOLS/llvm/bin:$NXO_TOOLS/cmake/bin:$NXO_TOOLS/ninja:$NXO_TOOLS/glslang/bin:$PATH"
    # Ubuntu ships the LLVM 18 binutils unversioned only in /usr/lib/llvm-18/bin.
    [ -d /usr/lib/llvm-18/bin ] && export PATH="/usr/lib/llvm-18/bin:$PATH"
    ;;
esac
export NXO_MIX
export PS4_SDK=$NXO_MIX/sdk-dl/orbis-sdk-v1/sdk
export PS4_COMPAT=$NXO_MIX/sdk-dl/orbis-sdk-v1/orbis-compat
export PS4_OVERLAY=$NXO_MIX/toolchain/include-overlay
export PS4_PREFIX=$NXO_MIX/prefix
export PS4_LIBCXX=$NXO_MIX/libcxx18
export PS4_CFLAGS="--target=x86_64-pc-freebsd12-elf -nostdlibinc -O2 -fPIC -funwind-tables -D__PS4__ -D__ORBIS__ -D_BSD_SOURCE=1 -U__FreeBSD__ -isysroot $PS4_SDK -isystem $PS4_OVERLAY -isystem $PS4_COMPAT/include -isystem $PS4_SDK/include -include orbis_prefix.h"
