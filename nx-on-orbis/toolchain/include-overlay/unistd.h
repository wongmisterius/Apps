/* eden-ps4 overlay: musl declares environ only under _GNU_SOURCE; Boost.Process needs it. */
#include_next <unistd.h>
#ifndef EDEN_PS4_UNISTD_ENVIRON
#define EDEN_PS4_UNISTD_ENVIRON
#ifdef __cplusplus
extern "C" {
#endif
extern char **environ;
#ifdef __cplusplus
}
#endif
#endif
