/* eden-ps4 overlay: the toolchain force-includes orbis_prefix.h everywhere, and OpenSSL/FFmpeg
 * also preprocess their assembly with the C flags; C type headers break an assembler. */
#ifndef __ASSEMBLER__
#include_next <orbis_prefix.h>
#endif
