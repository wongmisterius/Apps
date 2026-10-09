# source this: tools for building C deps (OpenSSL, FFmpeg...) for the PS4 from Git Bash
P=/c/Users/alejo/eden-ps4/tools/msys-perl/usr
export PERL5LIB=$P/share/perl5/core_perl:$P/lib/perl5/core_perl
export PATH="/c/Users/alejo/eden-ps4/tools/bin:$P/bin:/c/Users/alejo/eden-ps4/tools/usr/bin:/c/Users/alejo/soh-ps4/tools/llvm/bin:/c/Users/alejo/soh-ps4/tools/cmake-4.4.3-windows-x86_64/bin:/c/Users/alejo/soh-ps4/tools/ninja:/c/Users/alejo/soh-ps4/tools/glslang/bin:$PATH"
export PS4_SDK=C:/Users/alejo/eden-ps4/sdk-dl/orbis-sdk-v1/sdk
export PS4_COMPAT=C:/Users/alejo/eden-ps4/sdk-dl/orbis-sdk-v1/orbis-compat
export PS4_OVERLAY=C:/Users/alejo/eden-ps4/toolchain/include-overlay
export PS4_PREFIX=C:/Users/alejo/eden-ps4/prefix
export PS4_CFLAGS="--target=x86_64-pc-freebsd12-elf -O2 -fPIC -funwind-tables -D__PS4__ -D__ORBIS__ -D_BSD_SOURCE=1 -U__FreeBSD__ -isysroot $PS4_SDK -isystem $PS4_OVERLAY -isystem $PS4_COMPAT/include -isystem $PS4_SDK/include -include orbis_prefix.h"
