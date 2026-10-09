/* eden-ps4 overlay: the OpenOrbis musl defines strtof_l/strtod_l/strtold_l (weak, in libc.a) but
 * declares them only under _GNU_SOURCE; libc++ 18's musl locale layer calls them. */
#include_next <stdlib.h>
#ifndef EDEN_PS4_STDLIB_L
#define EDEN_PS4_STDLIB_L
#ifdef __cplusplus
extern "C" {
#endif
struct __locale_struct;
float strtof_l(const char *__restrict, char **__restrict, struct __locale_struct *);
double strtod_l(const char *__restrict, char **__restrict, struct __locale_struct *);
long double strtold_l(const char *__restrict, char **__restrict, struct __locale_struct *);
#ifdef __cplusplus
}
#endif
#endif
