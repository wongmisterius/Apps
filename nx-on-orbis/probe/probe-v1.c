/* eden-probe: can a PS4 Pro (GoldHEN) host a Switch emulator at all?
 *
 * One package that measures the things that decide whether an Eden/ProsperoEden port is
 * worth starting, before any emulator code is touched:
 *
 *   INFO     firmware, Neo (Pro) mode, CPU/TSC clocks, memory pools at boot
 *   DIRECT   how much direct memory this process can really allocate, map and touch
 *   FLEX     how much flexible memory
 *   VA       how much address space can be reserved (fastmem wants 512 GiB)
 *   ALIAS    the same physical pages mapped twice, and MAP_FIXED into a reservation
 *   VMAP     the process's address-space layout, for placing a JIT near the code
 *   JIT      write x86-64 into memory and run it (map RW, promote with mprotect)
 *   JITBIG   128 MiB and 512 MiB code caches; a cache within rel32 reach of the eboot
 *   JITRX    can a promoted range be narrowed to RX and widened again
 *   THREADS  one spinning thread pinned to each core: which cores exist, how fast
 *   VULKAN   RADV: device, version, features, extensions, memory ceiling, compute, present
 *   POSTVK   direct memory left once the GPU driver has taken its arena
 *   FAULT    a load from an unmapped page, resumed by rewriting rip in the handler
 *
 * Every line goes to /data/edenprobe/probe.log and is synced before the next step, so a crash
 * still leaves the line that names what it was doing. Each stage is recorded in
 * /data/edenprobe/progress.txt when it begins and when it ends; a stage that began and never
 * ended crashed or hung, and the next launch skips it and carries on with the rest.
 *
 * The screen shows the triangle on a background whose colour is the verdict:
 *   green  everything an emulator needs works
 *   amber  it works, with limits the log spells out
 *   red    a blocker (no JIT, no Vulkan, or not enough memory)
 */
#include <orbis_prefix.h>

#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#include <vulkan/vulkan.h>

#include "tri_vert_spv.h"
#include "tri_frag_spv.h"
#include "fill_comp_spv.h"

/* ------------------------------------------------------------------ libkernel, by asm label
 * Declared here rather than through <orbis/libkernel.h>: several of the SDK's prototypes are
 * wrong (AvailableFlexibleMemorySize takes its result by value, AvailableDirectMemorySize too),
 * and a second declaration of the same name would not compile. */
extern int32_t  k_alloc_direct(off_t start, off_t end, size_t len, size_t align, int32_t type, off_t *phys) __asm__("sceKernelAllocateDirectMemory");
extern int32_t  k_map_direct(void **addr, size_t len, int32_t prot, int32_t flags, off_t phys, size_t align) __asm__("sceKernelMapDirectMemory");
extern int32_t  k_release_direct(off_t phys, size_t len) __asm__("sceKernelReleaseDirectMemory");
extern int32_t  k_munmap(void *addr, size_t len) __asm__("sceKernelMunmap");
extern int32_t  k_mprotect(const void *addr, size_t len, int prot) __asm__("sceKernelMprotect");
extern size_t   k_direct_size(void) __asm__("sceKernelGetDirectMemorySize");
extern int32_t  k_direct_avail(off_t start, off_t end, size_t align, off_t *phys, size_t *size) __asm__("sceKernelAvailableDirectMemorySize");
extern int32_t  k_flex_avail(size_t *size) __asm__("sceKernelAvailableFlexibleMemorySize");
extern int32_t  k_map_flex(void **addr, size_t len, int32_t prot, int flags) __asm__("sceKernelMapFlexibleMemory");
extern int32_t  k_reserve(void **addr, size_t len, int32_t flags, size_t align) __asm__("sceKernelReserveVirtualRange");
extern int32_t  k_vquery(const void *addr, int flags, void *info, size_t infosize) __asm__("sceKernelVirtualQuery");
extern int32_t  k_is_neo(void) __asm__("sceKernelIsNeoMode");
extern uint64_t k_cpu_freq(void) __asm__("sceKernelGetCpuFrequency");
extern uint64_t k_tsc_freq(void) __asm__("sceKernelGetTscFrequency");
extern uint64_t k_process_time(void) __asm__("sceKernelGetProcessTime");
extern int32_t  k_sw_version(void *v) __asm__("sceKernelGetSystemSwVersion");
extern int32_t  k_open(const char *path, int flags, int mode) __asm__("sceKernelOpen");
extern int64_t  k_write(int fd, const void *buf, size_t len) __asm__("sceKernelWrite");
extern int64_t  k_read(int fd, void *buf, size_t len) __asm__("sceKernelRead");
extern int32_t  k_close(int fd) __asm__("sceKernelClose");
extern int32_t  k_fsync(int fd) __asm__("sceKernelFsync");
extern int32_t  k_mkdir(const char *path, int mode) __asm__("sceKernelMkdir");
extern int32_t  k_usleep(uint32_t us) __asm__("sceKernelUsleep");
extern int32_t  k_debug_out(int ch, const char *text) __asm__("sceKernelDebugOutText");
extern int      k_install_exc(int sig, void (*h)(int, void *)) __asm__("sceKernelInstallExceptionHandler");
extern int      k_remove_exc(int sig) __asm__("sceKernelRemoveExceptionHandler");
extern void    *k_pthread_self(void) __asm__("scePthreadSelf");
extern int      k_pthread_setaffinity(void *thr, uint64_t mask) __asm__("scePthreadSetaffinity");
extern int      k_current_cpu(void) __asm__("sceKernelGetCurrentCpu");

#define KO_WRONLY 0x0001
#define KO_RDONLY 0x0000
#define KO_APPEND 0x0008
#define KO_CREAT  0x0200
#define KO_TRUNC  0x0400

#define PROT_RW   0x03
#define PROT_RX   0x05
#define PROT_RWX  0x07
#define KMAP_FIXED 0x0010
#define WB_ONION  0
#define GRANULE   0x4000ull          /* the kernel's page for mapping and protection */

/* FreeBSD signal numbers - what the exception API takes. */
#define XSIGILL  4
#define XSIGFPE  8
#define XSIGBUS  10
#define XSIGSEGV 11

/* PS4 ucontext: 16-byte mask + 48 padding, then a FreeBSD amd64 mcontext. */
#define MC_OFF   64
#define MC_RAX   56
#define MC_ADDR  136
#define MC_RIP   160
#define MC_RSP   184

#define KiB (1024ull)
#define MiB (1024ull * KiB)
#define GiB (1024ull * MiB)

/* ------------------------------------------------------------------ logging */
#define DIR_PATH  "/data/edenprobe"
#define LOG_PATH  DIR_PATH "/probe.log"
#define PROG_PATH DIR_PATH "/progress.txt"
#define DRV_PATH  DIR_PATH "/driver.log"

static int g_log = -1;
static volatile const char *g_stage = "start";
static uint64_t g_t0;

static uint64_t now_us(void) { return k_process_time(); }

static void say(const char *fmt, ...)
{
    char buf[1024];
    int n = snprintf(buf, sizeof(buf), "[%7.3f] ", (double)(now_us() - g_t0) / 1e6);
    va_list ap;
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof(buf) - n - 2, fmt, ap);
    va_end(ap);
    if (n > (int)sizeof(buf) - 2) n = sizeof(buf) - 2;
    buf[n++] = '\n';
    buf[n] = 0;
    k_debug_out(0, buf);
    if (g_log >= 0) {
        k_write(g_log, buf, n);
        k_fsync(g_log);
    }
}

static const char *mib(uint64_t bytes)
{
    static char ring[8][32];
    static int  i;
    char *s = ring[i++ & 7];
    snprintf(s, 32, "%.1f MiB", (double)bytes / MiB);
    return s;
}

/* ------------------------------------------------------------------ stage bookkeeping */
static char g_prog[16384];
static int  g_prog_fd = -1;

static void prog_load(void)
{
    int fd = k_open(PROG_PATH, KO_RDONLY, 0);
    if (fd >= 0) {
        int64_t n = k_read(fd, g_prog, sizeof(g_prog) - 1);
        g_prog[n > 0 ? n : 0] = 0;
        k_close(fd);
    }
    /* A finished run starts the next one fresh. */
    int fresh = strstr(g_prog, "DONE\n") != NULL || g_prog[0] == 0;
    if (fresh) g_prog[0] = 0;
    g_prog_fd = k_open(PROG_PATH, KO_WRONLY | KO_CREAT | (fresh ? KO_TRUNC : KO_APPEND), 0666);
}

static void prog_mark(const char *what, const char *id)
{
    char line[64];
    int n = snprintf(line, sizeof(line), "%s:%s\n", what, id);
    if (g_prog_fd >= 0) {
        k_write(g_prog_fd, line, n);
        k_fsync(g_prog_fd);
    }
}

static int prog_has(const char *what, const char *id)
{
    char key[64];
    snprintf(key, sizeof(key), "%s:%s\n", what, id);
    return strstr(g_prog, key) != NULL;
}

static volatile uint64_t g_deadline;   /* 0 = no stage running */
static int  g_timeout_s;
static int  g_crashed_before;
static char g_crashed_list[256];

static int stage_begin(const char *id, int timeout_s, const char *what)
{
    if (prog_has("B", id) && !prog_has("E", id)) {
        say("== %s SKIPPED - it crashed or hung on an earlier launch (see above in this log)", id);
        g_crashed_before++;
        strncat(g_crashed_list, id, sizeof(g_crashed_list) - strlen(g_crashed_list) - 2);
        strcat(g_crashed_list, " ");
        return 0;
    }
    prog_mark("B", id);
    g_stage = id;
    g_timeout_s = timeout_s;
    say("== %s: %s", id, what);
    g_deadline = now_us() + (uint64_t)timeout_s * 1000000ull;
    return 1;
}

static void stage_end(const char *id)
{
    g_deadline = 0;
    prog_mark("E", id);
    say("== %s done", id);
    g_stage = "between stages";
}

static void *watchdog(void *arg)
{
    (void)arg;
    for (;;) {
        k_usleep(500 * 1000);
        uint64_t d = g_deadline;
        if (d && now_us() > d) {
            say("!! HANG: stage %s ran past %d s. It stays marked, so the next launch skips it.",
                (const char *)g_stage, g_timeout_s);
            say("!! Close the app (PS button) and open it again to run the remaining stages.");
            k_usleep(300 * 1000);
            _exit(3);
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ fault handling */
extern int probe_fault_load(const void *addr);
extern char probe_fault_resume[];
__asm__(
    ".text\n"
    ".globl probe_fault_load\n"
    "probe_fault_load:\n"
    "    movl $0x1111, %eax\n"
    "    movq (%rdi), %rax\n"          /* faults on an unmapped page */
    ".globl probe_fault_resume\n"
    "probe_fault_resume:\n"
    "    ret\n");

static volatile int      g_fault_expect;
static volatile int      g_fault_hits;
static volatile uint64_t g_fault_addr;

static void exc_handler(int sig, void *uc)
{
    uint8_t  *mc  = (uint8_t *)uc + MC_OFF;
    uint64_t *rip = (uint64_t *)(mc + MC_RIP);
    if (g_fault_expect && g_fault_hits < 4) {
        g_fault_hits++;
        g_fault_addr = *(uint64_t *)(mc + MC_ADDR);
        *rip = (uint64_t)(uintptr_t)probe_fault_resume;
        *(uint64_t *)(mc + MC_RAX) = 0xFA57;
        return;
    }
    say("!! CRASH: signal %d in stage %s, rip=0x%lx (eboot+0x%lx), fault address 0x%lx, rsp=0x%lx",
        sig, (const char *)g_stage, *rip, *rip - 0x400000ull,
        *(uint64_t *)(mc + MC_ADDR), *(uint64_t *)(mc + MC_RSP));
    say("!! The stage stays marked, so the next launch skips it. Open the app again.");
    /* Hand the fault back to the system: with our handler gone it ends the process. */
    k_remove_exc(XSIGSEGV);
    k_remove_exc(XSIGBUS);
    k_remove_exc(XSIGILL);
    k_remove_exc(XSIGFPE);
}

/* ------------------------------------------------------------------ results */
static uint64_t r_direct_total, r_direct_pre, r_direct_post, r_flex, r_va_max;
static int      r_direct_done, r_post_done, r_flex_done, r_va_done;
static int      r_alias = -1, r_fixed = -1, r_jit = -1, r_jit_near = -1, r_jit128 = -1, r_jit512 = -1;
static int      r_rx_narrow = -1, r_rx_widen = -1, r_fault = -1, r_neo = -1;
static double   r_fault_us;
static int      r_cores_ok = -1;
static double   r_mops_single;
static int      r_vk = -1, r_vk_compute = -1, r_vk_present = -1;
static uint32_t r_vk_api;
static uint64_t r_vk_local, r_vk_host;
static double   r_vk_fps;
static double   r_bw_write;

/* ------------------------------------------------------------------ stages */
static void st_info(void)
{
    struct { uint64_t size; char str[0x1c]; uint32_t hex; } ver;
    memset(&ver, 0, sizeof(ver));
    ver.size = sizeof(ver);
    int rc = k_sw_version(&ver);
    say("firmware: %s (0x%08x) rc=0x%08x", rc == 0 ? ver.str : "?", ver.hex, rc);

    r_neo = k_is_neo();
    say("Neo (PS4 Pro) mode: %d", r_neo);
    say("CPU frequency: %.0f MHz, TSC %.0f MHz", (double)k_cpu_freq() / 1e6, (double)k_tsc_freq() / 1e6);

    r_direct_total = k_direct_size();
    say("direct memory size: %s", mib(r_direct_total));
    off_t phys = 0;
    size_t sz = 0;
    rc = k_direct_avail(0, (off_t)r_direct_total, 0, &phys, &sz);
    say("largest free direct block: %s at phys 0x%lx (rc=0x%08x)", mib(sz), (unsigned long)phys, rc);
    size_t flex = 0;
    rc = k_flex_avail(&flex);
    say("flexible memory available: %s (rc=0x%08x)", mib(flex), rc);
    say("main() at %p, a global at %p, a local at %p", (void *)&st_info, (void *)&g_prog, (void *)&rc);
}

typedef struct { off_t phys; void *va; size_t len; } dblock;
static dblock g_blocks[512];

/* Allocates, maps and touches direct memory until the kernel says no; returns the total.
 * Everything is released before returning. */
static uint64_t direct_ladder(int measure_bw)
{
    static const size_t steps[] = { 256 * MiB, 64 * MiB, 16 * MiB };
    int n = 0;
    uint64_t total = 0;
    for (int s = 0; s < 3; s++) {
        size_t len = steps[s];
        for (;;) {
            if (n >= (int)(sizeof(g_blocks) / sizeof(g_blocks[0]))) break;
            off_t phys = 0;
            int rc = k_alloc_direct(0, (off_t)k_direct_size(), len, 2 * MiB, WB_ONION, &phys);
            if (rc) {
                say("  alloc %s refused: 0x%08x (after %s)", mib(len), rc, mib(total));
                break;
            }
            void *va = NULL;
            rc = k_map_direct(&va, len, PROT_RW, 0, phys, 2 * MiB);
            if (rc) {
                say("  map %s refused: 0x%08x (after %s)", mib(len), rc, mib(total));
                k_release_direct(phys, len);
                break;
            }
            volatile uint8_t *p = va;
            for (size_t o = 0; o < len; o += GRANULE) p[o] = (uint8_t)o;
            g_blocks[n].phys = phys;
            g_blocks[n].va = va;
            g_blocks[n].len = len;
            n++;
            total += len;
        }
    }
    say("  reached %s in %d blocks", mib(total), n);
    if (measure_bw && n > 0 && g_blocks[0].len >= 256 * MiB) {
        uint64_t t = now_us();
        for (int i = 0; i < 4; i++) memset(g_blocks[0].va, i, 256 * MiB);
        double s = (double)(now_us() - t) / 1e6;
        r_bw_write = 1024.0 / s;
        say("  memset bandwidth: %.0f MiB/s", r_bw_write);
        if (n > 1 && g_blocks[1].len >= 256 * MiB) {
            t = now_us();
            for (int i = 0; i < 4; i++) memcpy(g_blocks[1].va, g_blocks[0].va, 256 * MiB);
            s = (double)(now_us() - t) / 1e6;
            say("  memcpy bandwidth: %.0f MiB/s", 1024.0 / s);
        }
    }
    for (int i = n - 1; i >= 0; i--) {
        k_munmap(g_blocks[i].va, g_blocks[i].len);
        k_release_direct(g_blocks[i].phys, g_blocks[i].len);
    }
    return total;
}

static void st_direct(void)
{
    r_direct_pre = direct_ladder(1);
    r_direct_done = 1;
    say("DIRECT: %s usable before the GPU driver", mib(r_direct_pre));
}

static void st_flex(void)
{
    void *va[256];
    int n = 0;
    uint64_t total = 0;
    while (n < 256) {
        void *a = NULL;
        int rc = k_map_flex(&a, 16 * MiB, PROT_RW, 0);
        if (rc) {
            say("  flexible 16 MiB refused: 0x%08x", rc);
            break;
        }
        volatile uint8_t *p = a;
        for (size_t o = 0; o < 16 * MiB; o += GRANULE) p[o] = 1;
        va[n++] = a;
        total += 16 * MiB;
    }
    for (int i = 0; i < n; i++) k_munmap(va[i], 16 * MiB);
    r_flex = total;
    r_flex_done = 1;
    say("FLEX: %s of flexible memory mapped and touched", mib(total));
}

static void st_va(void)
{
    static const uint64_t sizes[] = { 1 * GiB, 4 * GiB, 8 * GiB, 16 * GiB, 64 * GiB, 128 * GiB, 256 * GiB, 512 * GiB };
    for (unsigned i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        void *a = NULL;
        int rc = k_reserve(&a, sizes[i], 0, 2 * MiB);
        say("  reserve %4lu GiB -> rc=0x%08x at %p", (unsigned long)(sizes[i] / GiB), rc, a);
        if (rc == 0) {
            r_va_max = sizes[i];
            k_munmap(a, sizes[i]);
        }
    }
    r_va_done = 1;
    say("VA: largest reservation %lu GiB (Eden's fastmem wants 512)", (unsigned long)(r_va_max / GiB));
}

static void st_alias(void)
{
    const size_t len = 16 * MiB;
    off_t phys;
    int rc = k_alloc_direct(0, (off_t)k_direct_size(), len, 2 * MiB, WB_ONION, &phys);
    if (rc) { say("  alloc failed 0x%08x", rc); r_alias = 0; return; }
    void *a = NULL, *b = NULL;
    int ra = k_map_direct(&a, len, PROT_RW, 0, phys, 2 * MiB);
    int rb = k_map_direct(&b, len, PROT_RW, 0, phys, 2 * MiB);
    say("  first map rc=0x%08x at %p, second map of the same pages rc=0x%08x at %p", ra, a, rb, b);
    if (ra == 0 && rb == 0) {
        ((volatile uint32_t *)a)[0] = 0x12345678;
        ((volatile uint32_t *)b)[1000] = 0x9abcdef0;
        uint32_t x = ((volatile uint32_t *)b)[0], y = ((volatile uint32_t *)a)[1000];
        r_alias = (x == 0x12345678 && y == 0x9abcdef0);
        say("  read back through the other view: 0x%08x 0x%08x -> %s", x, y, r_alias ? "ALIAS WORKS" : "NO ALIAS");
    } else {
        r_alias = 0;
    }
    if (rb == 0) k_munmap(b, len);
    if (ra == 0) k_munmap(a, len);

    /* MAP_FIXED into the middle of a reservation we own: how a page table of host mappings is built. */
    void *res = NULL;
    rc = k_reserve(&res, 64 * MiB, 0, 2 * MiB);
    if (rc == 0) {
        void *at = (uint8_t *)res + 16 * MiB;
        void *got = at;
        rc = k_map_direct(&got, len, PROT_RW, KMAP_FIXED, phys, 2 * MiB);
        r_fixed = (rc == 0 && got == at);
        if (r_fixed) ((volatile uint32_t *)got)[7] = 7;
        say("  MAP_FIXED inside a reservation: rc=0x%08x wanted %p got %p -> %s", rc, at, got, r_fixed ? "OK" : "FAILED");
        k_munmap(res, 64 * MiB);
    } else {
        say("  reservation for MAP_FIXED failed 0x%08x", rc);
        r_fixed = 0;
    }
    k_release_direct(phys, len);
}

typedef struct {
    void   *start;
    void   *end;
    off_t   offset;
    int32_t prot;
    int32_t mtype;
    uint8_t bits;
    char    name[32];
} vq_info;

static void st_vmap(void)
{
    const uint8_t *p = (const uint8_t *)0;
    for (int i = 0; i < 60; i++) {
        vq_info info;
        memset(&info, 0, sizeof(info));
        int rc = k_vquery(p, 1 /* find next */, &info, sizeof(info));
        if (rc) { say("  (end of map, rc=0x%08x)", rc); break; }
        say("  %012lx-%012lx prot %02x type %d bits %02x %s", (unsigned long)info.start,
            (unsigned long)info.end, info.prot, info.mtype, info.bits, info.name);
        if ((const uint8_t *)info.end <= p) break;
        p = info.end;
    }
}

/* Free means: nothing contains `at`, and the next mapping starts at or after at+len. Both
 * questions are asked, because a MAP_FIXED on a wrong answer would replace live memory. */
static int range_free(uintptr_t at, size_t len)
{
    vq_info info;
    memset(&info, 0, sizeof(info));
    if (k_vquery((const void *)at, 0, &info, sizeof(info)) == 0 &&
        (uintptr_t)info.start <= at && at < (uintptr_t)info.end)
        return 0;
    memset(&info, 0, sizeof(info));
    if (k_vquery((const void *)at, 1, &info, sizeof(info)) != 0)
        return 1;                                     /* nothing mapped at or after it */
    return (uintptr_t)info.start >= at + len;
}

typedef struct { off_t phys; void *va; size_t len; } jit_arena;

/* Map read-write, then promote to RWX: the only order this kernel grants. */
static int jit_map(jit_arena *j, size_t len, void *fixed_at)
{
    memset(j, 0, sizeof(*j));
    int rc = k_alloc_direct(0, (off_t)k_direct_size(), len, GRANULE, WB_ONION, &j->phys);
    if (rc) { say("  jit: no direct memory for %s: 0x%08x", mib(len), rc); return 0; }
    j->va = fixed_at;
    rc = k_map_direct(&j->va, len, PROT_RW, fixed_at ? KMAP_FIXED : 0, j->phys, GRANULE);
    if (rc) { say("  jit: map %s refused: 0x%08x", mib(len), rc); k_release_direct(j->phys, len); return 0; }
    j->len = len;
    rc = k_mprotect(j->va, len, PROT_RWX);
    if (rc) {
        say("  jit: mprotect RWX on %s at %p refused: 0x%08x", mib(len), j->va, rc);
        k_munmap(j->va, len);
        k_release_direct(j->phys, len);
        j->len = 0;
        return 0;
    }
    return 1;
}

static void jit_unmap(jit_arena *j)
{
    if (!j->len) return;
    k_munmap(j->va, j->len);
    k_release_direct(j->phys, j->len);
    j->len = 0;
}

static int run_stub(void *at, uint32_t value)
{
    uint8_t *c = at;
    c[0] = 0xB8;                                      /* mov eax, imm32 */
    memcpy(c + 1, &value, 4);
    c[5] = 0xC3;                                      /* ret */
    say("  calling generated code at %p (expect 0x%08x)", at);
    uint32_t got = ((uint32_t (*)(void))at)();
    say("  it returned 0x%08x -> %s", got, got == value ? "RUNS" : "WRONG");
    return got == value;
}

static uint32_t host_fn(void) { return 0x51505150u; }

static void st_jit(void)
{
    jit_arena j;
    if (!jit_map(&j, 16 * MiB, NULL)) { r_jit = 0; return; }
    long dist = (long)((uintptr_t)j.va - (uintptr_t)&st_jit);
    say("  16 MiB RWX at %p, %ld MiB from the eboot's code", j.va, dist / (long)MiB);
    r_jit = run_stub(j.va, 0x00C0FFEE);
    if (r_jit) r_jit = run_stub((uint8_t *)j.va + 15 * MiB, 0x0BADF00D);   /* far end of the arena */
    jit_unmap(&j);
}

static void st_jitbig(void)
{
    jit_arena j;
    r_jit128 = jit_map(&j, 128 * MiB, NULL);
    if (r_jit128) { r_jit128 = run_stub((uint8_t *)j.va + 127 * MiB, 0x128); jit_unmap(&j); }
    r_jit512 = jit_map(&j, 512 * MiB, NULL);
    if (r_jit512) { r_jit512 = run_stub((uint8_t *)j.va + 511 * MiB, 0x512); jit_unmap(&j); }

    /* A cache within +-2 GiB of the eboot, where a rel32 call can reach host functions. */
    const size_t len = 64 * MiB;
    uintptr_t text = (uintptr_t)&host_fn;
    uintptr_t found = 0;
    uintptr_t lo = ((uintptr_t)&g_blocks + sizeof(g_blocks) + 64 * MiB + 16 * MiB - 1) & ~(uintptr_t)(16 * MiB - 1);
    if (lo < 0x10000000) lo = 0x10000000;
    for (uintptr_t at = lo; at < 0x70000000; at += 16 * MiB) {
        if (range_free(at, len)) { found = at; break; }
    }
    if (!found) {
        say("  no free 64 MiB window found below 1.75 GiB");
        r_jit_near = 0;
        return;
    }
    say("  free window at 0x%lx; mapping a cache there with MAP_FIXED", (unsigned long)found);
    if (!jit_map(&j, len, (void *)found)) { r_jit_near = 0; return; }
    uint8_t *c = j.va;
    int64_t rel = (int64_t)text - (int64_t)((uintptr_t)c + 4 + 5);
    if (rel > INT32_MAX || rel < INT32_MIN) {
        say("  window still out of rel32 reach (%ld)", (long)rel);
        r_jit_near = 0;
        jit_unmap(&j);
        return;
    }
    int32_t r32 = (int32_t)rel;
    c[0] = 0x48; c[1] = 0x83; c[2] = 0xEC; c[3] = 0x08;          /* sub rsp, 8 */
    c[4] = 0xE8; memcpy(c + 5, &r32, 4);                        /* call rel32 host_fn */
    c[9] = 0x48; c[10] = 0x83; c[11] = 0xC4; c[12] = 0x08;      /* add rsp, 8 */
    c[13] = 0xC3;
    say("  calling generated code that calls back into the eboot with rel32");
    uint32_t got = ((uint32_t (*)(void))c)();
    r_jit_near = got == 0x51505150u;
    say("  it returned 0x%08x -> %s", got, r_jit_near ? "NEAR CACHE WORKS" : "WRONG");
    jit_unmap(&j);
}

static void st_jitrx(void)
{
    jit_arena j;
    if (!jit_map(&j, 1 * MiB, NULL)) return;
    run_stub(j.va, 1);
    int rc = k_mprotect(j.va, j.len, PROT_RX);
    r_rx_narrow = rc == 0;
    say("  narrow to RX: rc=0x%08x", rc);
    if (rc == 0) {
        uint32_t got = ((uint32_t (*)(void))j.va)();
        say("  still runs after RX: 0x%x", got);
    }
    rc = k_mprotect(j.va, j.len, PROT_RWX);
    r_rx_widen = rc == 0;
    say("  widen back to RWX: rc=0x%08x", rc);
    if (rc == 0) run_stub(j.va, 2);                   /* writes: faults if the widen was not honoured */
    jit_unmap(&j);
}

typedef struct { int core; int rc; int seen; uint64_t iters; } spin_arg;
static volatile uint64_t g_sink;

static void *spin(void *p)
{
    spin_arg *a = p;
    if (a->core >= 0) a->rc = k_pthread_setaffinity(k_pthread_self(), 1ull << a->core);
    k_usleep(2000);
    a->seen = k_current_cpu();
    uint64_t end = now_us() + 400000, x = 1, n = 0;
    while (now_us() < end) {
        for (int i = 0; i < 20000; i++) x = x * 6364136223846793005ull + 1442695040888963407ull;
        n += 20000;
    }
    g_sink ^= x;
    a->iters = n;
    return NULL;
}

static void st_threads(void)
{
    spin_arg one = { .core = -1 };
    pthread_t t;
    pthread_create(&t, NULL, spin, &one);
    pthread_join(t, NULL);
    r_mops_single = (double)one.iters / 0.4 / 1e6;
    say("  one thread alone: %.0f M dependent mul-adds/s (on cpu %d)", r_mops_single, one.seen);

    spin_arg a[8];
    pthread_t th[8];
    for (int i = 0; i < 8; i++) {
        memset(&a[i], 0, sizeof(a[i]));
        a[i].core = i;
        pthread_create(&th[i], NULL, spin, &a[i]);
    }
    int ok = 0;
    for (int i = 0; i < 8; i++) {
        pthread_join(th[i], NULL);
        double m = (double)a[i].iters / 0.4 / 1e6;
        say("  core %d: affinity rc=0x%08x, ran on cpu %d, %.0f M/s (%.0f%% of alone)",
            i, a[i].rc, a[i].seen, m, 100.0 * m / (r_mops_single > 0 ? r_mops_single : 1));
        if (a[i].rc == 0 && m > 0.6 * r_mops_single) ok++;
    }
    r_cores_ok = ok;
    say("THREADS: %d cores ran at more than 60%% of a lone thread's speed", ok);
}

/* ------------------------------------------------------------------ Vulkan */
static VkInstance       g_inst;
static VkPhysicalDevice g_phys;
static VkDevice         g_dev;
static VkQueue          g_queue;
static uint32_t         g_qf = UINT32_MAX;
static VkSurfaceKHR     g_surface;
static VkSwapchainKHR   g_swap;
static VkExtent2D       g_extent;
static VkRenderPass     g_rp;
static VkPipeline       g_pipe;
static VkFramebuffer   *g_fbs;
static uint32_t         g_nimg;
static VkCommandPool    g_pool;
static VkCommandBuffer  g_cmd;
static VkSemaphore      g_acq, g_ren;
static VkFence          g_fence;

#define VKCHECK(call, what)                                                   \
    do {                                                                      \
        VkResult _r = (call);                                                 \
        if (_r != VK_SUCCESS) {                                               \
            say("  VULKAN FAILED at %s -> VkResult %d", what, (int)_r);        \
            return 0;                                                         \
        }                                                                     \
    } while (0)

static int has_ext(VkExtensionProperties *e, uint32_t n, const char *name)
{
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

static uint32_t find_type(VkPhysicalDeviceMemoryProperties *mp, uint32_t bits, VkMemoryPropertyFlags want)
{
    for (uint32_t i = 0; i < mp->memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp->memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

static uint64_t alloc_ceiling(uint32_t type, uint64_t cap)
{
    static VkDeviceMemory mem[64];
    int n = 0;
    uint64_t total = 0;
    while (n < 64 && total < cap) {
        VkMemoryAllocateInfo ai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                    .allocationSize = 64 * MiB, .memoryTypeIndex = type };
        VkResult r = vkAllocateMemory(g_dev, &ai, NULL, &mem[n]);
        if (r != VK_SUCCESS) { say("  vkAllocateMemory(64 MiB) -> %d after %s", (int)r, mib(total)); break; }
        n++;
        total += 64 * MiB;
    }
    for (int i = 0; i < n; i++) vkFreeMemory(g_dev, mem[i], NULL);
    return total;
}

static int vk_init(void)
{
    uint32_t iv = VK_API_VERSION_1_0;
    vkEnumerateInstanceVersion(&iv);
    say("  instance version %u.%u.%u", VK_VERSION_MAJOR(iv), VK_VERSION_MINOR(iv), VK_VERSION_PATCH(iv));

    const char *iext[] = { "VK_KHR_surface", "VK_EXT_headless_surface" };
    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "eden-probe",
                              .apiVersion = iv };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app,
                                 .enabledExtensionCount = 2, .ppEnabledExtensionNames = iext };
    VKCHECK(vkCreateInstance(&ici, NULL, &g_inst), "vkCreateInstance");
    VkHeadlessSurfaceCreateInfoEXT hs = { .sType = VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT };
    VKCHECK(vkCreateHeadlessSurfaceEXT(g_inst, &hs, NULL, &g_surface), "vkCreateHeadlessSurfaceEXT");

    uint32_t nd = 1;
    VkResult er = vkEnumeratePhysicalDevices(g_inst, &nd, &g_phys);
    if ((er != VK_SUCCESS && er != VK_INCOMPLETE) || nd == 0) { say("  no physical device (%d)", (int)er); return 0; }

    VkPhysicalDeviceProperties pr;
    vkGetPhysicalDeviceProperties(g_phys, &pr);
    r_vk_api = pr.apiVersion;
    say("  GPU '%s', Vulkan %u.%u.%u, driver 0x%08x, vendor 0x%04x device 0x%04x", pr.deviceName,
        VK_VERSION_MAJOR(pr.apiVersion), VK_VERSION_MINOR(pr.apiVersion), VK_VERSION_PATCH(pr.apiVersion),
        pr.driverVersion, pr.vendorID, pr.deviceID);
    VkPhysicalDeviceLimits *L = &pr.limits;
    say("  limits: image2D %u, storage buffer range %u, uniform range %u, push constants %u, compute shared %u,"
        " workgroup %u x %u x %u (inv %u), viewports %u, vertex attribs %u, sampler allocs %u, alloc count %u",
        L->maxImageDimension2D, L->maxStorageBufferRange, L->maxUniformBufferRange, L->maxPushConstantsSize,
        L->maxComputeSharedMemorySize, L->maxComputeWorkGroupSize[0], L->maxComputeWorkGroupSize[1],
        L->maxComputeWorkGroupSize[2], L->maxComputeWorkGroupInvocations, L->maxViewports,
        L->maxVertexInputAttributes, L->maxSamplerAllocationCount, L->maxMemoryAllocationCount);

    VkPhysicalDeviceVulkan12Features f12 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES };
    VkPhysicalDeviceVulkan11Features f11 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, .pNext = &f12 };
    VkPhysicalDeviceFeatures2 f2 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &f11 };
    vkGetPhysicalDeviceFeatures2(g_phys, &f2);
    VkPhysicalDeviceFeatures *f = &f2.features;
    say("  features: geometry %d tess %d int64 %d int16 %d float64 %d multiViewport %d logicOp %d depthClamp %d"
        " depthBiasClamp %d BC %d ETC2 %d ASTC %d aniso %d imageCubeArray %d independentBlend %d dualSrcBlend %d"
        " wideLines %d largePoints %d vertexStores %d fragmentStores %d readWithoutFormat %d writeWithoutFormat %d"
        " occlusionPrecise %d pipelineStats %d shaderClipDistance %d cullDistance %d robustBuffer %d"
        " drawIndirectFirstInstance %d multiDrawIndirect %d sampleRateShading %d",
        f->geometryShader, f->tessellationShader, f->shaderInt64, f->shaderInt16, f->shaderFloat64,
        f->multiViewport, f->logicOp, f->depthClamp, f->depthBiasClamp, f->textureCompressionBC,
        f->textureCompressionETC2, f->textureCompressionASTC_LDR, f->samplerAnisotropy, f->imageCubeArray,
        f->independentBlend, f->dualSrcBlend, f->wideLines, f->largePoints, f->vertexPipelineStoresAndAtomics,
        f->fragmentStoresAndAtomics, f->shaderStorageImageReadWithoutFormat, f->shaderStorageImageWriteWithoutFormat,
        f->occlusionQueryPrecise, f->pipelineStatisticsQuery, f->shaderClipDistance, f->shaderCullDistance,
        f->robustBufferAccess, f->drawIndirectFirstInstance, f->multiDrawIndirect, f->sampleRateShading);
    say("  1.1: storageBuffer16 %d uniform16 %d drawParameters %d multiview %d variablePointers %d",
        f11.storageBuffer16BitAccess, f11.uniformAndStorageBuffer16BitAccess, f11.shaderDrawParameters,
        f11.multiview, f11.variablePointers);
    say("  1.2: timelineSemaphore %d float16 %d int8 %d storageBuffer8 %d hostQueryReset %d"
        " descriptorIndexing %d bufferDeviceAddress %d scalarBlockLayout %d uniformStandardLayout %d"
        " memoryModel %d mirrorClampToEdge %d drawIndirectCount %d subgroupExtendedTypes %d",
        f12.timelineSemaphore, f12.shaderFloat16, f12.shaderInt8, f12.storageBuffer8BitAccess,
        f12.hostQueryReset, f12.descriptorIndexing, f12.bufferDeviceAddress, f12.scalarBlockLayout,
        f12.uniformBufferStandardLayout, f12.vulkanMemoryModel, f12.samplerMirrorClampToEdge,
        f12.drawIndirectCount, f12.shaderSubgroupExtendedTypes);

    uint32_t ne = 0;
    vkEnumerateDeviceExtensionProperties(g_phys, NULL, &ne, NULL);
    VkExtensionProperties *ext = calloc(ne ? ne : 1, sizeof(*ext));
    vkEnumerateDeviceExtensionProperties(g_phys, NULL, &ne, ext);
    say("  %u device extensions:", ne);
    char line[900];
    line[0] = 0;
    for (uint32_t i = 0; i < ne; i++) {
        if (strlen(line) + strlen(ext[i].extensionName) + 2 > sizeof(line) - 1) { say("    %s", line); line[0] = 0; }
        strcat(line, ext[i].extensionName);
        strcat(line, " ");
    }
    if (line[0]) say("    %s", line);
    static const char *eden_wants[] = {
        "VK_KHR_swapchain", "VK_EXT_transform_feedback", "VK_EXT_custom_border_color",
        "VK_EXT_extended_dynamic_state", "VK_EXT_extended_dynamic_state2", "VK_EXT_extended_dynamic_state3",
        "VK_EXT_vertex_input_dynamic_state", "VK_EXT_robustness2", "VK_EXT_shader_stencil_export",
        "VK_KHR_push_descriptor", "VK_EXT_depth_clip_control", "VK_EXT_depth_range_unrestricted",
        "VK_EXT_line_rasterization", "VK_EXT_index_type_uint8", "VK_EXT_primitive_topology_list_restart",
        "VK_EXT_provoking_vertex", "VK_EXT_conditional_rendering", "VK_EXT_shader_viewport_index_layer",
        "VK_KHR_shader_float_controls", "VK_EXT_subgroup_size_control", "VK_KHR_pipeline_executable_properties",
        "VK_EXT_memory_budget", "VK_KHR_maintenance5", "VK_EXT_shader_demote_to_helper_invocation",
        "VK_KHR_draw_indirect_count", "VK_EXT_sampler_filter_minmax", "VK_KHR_image_format_list",
        "VK_EXT_descriptor_indexing", "VK_KHR_timeline_semaphore",
    };
    int have = 0;
    char miss[600];
    miss[0] = 0;
    for (unsigned i = 0; i < sizeof(eden_wants) / sizeof(eden_wants[0]); i++) {
        if (has_ext(ext, ne, eden_wants[i])) have++;
        else if (strlen(miss) + strlen(eden_wants[i]) + 2 < sizeof(miss)) { strcat(miss, eden_wants[i]); strcat(miss, " "); }
    }
    say("  extensions Eden uses: %d of %u present; missing: %s", have,
        (unsigned)(sizeof(eden_wants) / sizeof(eden_wants[0])), miss[0] ? miss : "none");
    int has_swap = has_ext(ext, ne, "VK_KHR_swapchain");
    free(ext);

    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(g_phys, &mp);
    for (uint32_t i = 0; i < mp.memoryHeapCount; i++)
        say("  heap %u: %s flags 0x%x", i, mib(mp.memoryHeaps[i].size), mp.memoryHeaps[i].flags);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        say("  type %u: heap %u flags 0x%x", i, mp.memoryTypes[i].heapIndex, mp.memoryTypes[i].propertyFlags);

    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(g_phys, &nq, NULL);
    VkQueueFamilyProperties qfp[8];
    if (nq > 8) nq = 8;
    vkGetPhysicalDeviceQueueFamilyProperties(g_phys, &nq, qfp);
    for (uint32_t i = 0; i < nq; i++) {
        VkBool32 pres = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(g_phys, i, g_surface, &pres);
        say("  queue family %u: flags 0x%x count %u present %d", i, qfp[i].queueFlags, qfp[i].queueCount, pres);
        if (g_qf == UINT32_MAX && (qfp[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (qfp[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && pres)
            g_qf = i;
    }
    if (g_qf == UINT32_MAX) { say("  no graphics+compute+present queue"); return 0; }

    const float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                    .queueFamilyIndex = g_qf, .queueCount = 1, .pQueuePriorities = &prio };
    const char *dext[] = { "VK_KHR_swapchain" };
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
                               .pQueueCreateInfos = &qci, .enabledExtensionCount = has_swap ? 1 : 0,
                               .ppEnabledExtensionNames = dext };
    say("  creating the device (the driver takes its memory arena here)");
    VKCHECK(vkCreateDevice(g_phys, &dci, NULL, &g_dev), "vkCreateDevice");
    vkGetDeviceQueue(g_dev, g_qf, 0, &g_queue);
    r_vk = 1;

    off_t phys;
    size_t sz = 0;
    k_direct_avail(0, (off_t)k_direct_size(), 0, &phys, &sz);
    say("  largest free direct block after the device: %s", mib(sz));

    uint32_t tl = find_type(&mp, ~0u, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    uint32_t th = find_type(&mp, ~0u, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (tl != UINT32_MAX) { r_vk_local = alloc_ceiling(tl, 4 * GiB); say("  device-local allocations reached %s", mib(r_vk_local)); }
    if (th != UINT32_MAX) { r_vk_host = alloc_ceiling(th, 4 * GiB); say("  host-visible allocations reached %s", mib(r_vk_host)); }

    VkCommandPoolCreateInfo cpci = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                     .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = g_qf };
    VKCHECK(vkCreateCommandPool(g_dev, &cpci, NULL, &g_pool), "vkCreateCommandPool");
    VkCommandBufferAllocateInfo cbai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = g_pool,
                                         .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 };
    VKCHECK(vkAllocateCommandBuffers(g_dev, &cbai, &g_cmd), "vkAllocateCommandBuffers");
    VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VKCHECK(vkCreateFence(g_dev, &fci, NULL, &g_fence), "vkCreateFence");
    return 1;
}

static int vk_compute(void)
{
    const uint32_t N = 1 << 16;
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(g_phys, &mp);

    VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = N * 4,
                               .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE };
    VkBuffer buf;
    VKCHECK(vkCreateBuffer(g_dev, &bci, NULL, &buf), "vkCreateBuffer");
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(g_dev, buf, &req);
    uint32_t t = find_type(&mp, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (t == UINT32_MAX) { say("  no host-visible coherent type for the buffer"); return 0; }
    VkMemoryAllocateInfo ai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size, .memoryTypeIndex = t };
    VkDeviceMemory mem;
    VKCHECK(vkAllocateMemory(g_dev, &ai, NULL, &mem), "vkAllocateMemory(compute)");
    VKCHECK(vkBindBufferMemory(g_dev, buf, mem, 0), "vkBindBufferMemory");
    uint32_t *map;
    VKCHECK(vkMapMemory(g_dev, mem, 0, VK_WHOLE_SIZE, 0, (void **)&map), "vkMapMemory");
    memset(map, 0, N * 4);

    VkDescriptorSetLayoutBinding b = { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                       .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT };
    VkDescriptorSetLayoutCreateInfo dl = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 1, .pBindings = &b };
    VkDescriptorSetLayout dsl;
    VKCHECK(vkCreateDescriptorSetLayout(g_dev, &dl, NULL, &dsl), "vkCreateDescriptorSetLayout");
    VkPipelineLayoutCreateInfo plci = { .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &dsl };
    VkPipelineLayout pl;
    VKCHECK(vkCreatePipelineLayout(g_dev, &plci, NULL, &pl), "vkCreatePipelineLayout(compute)");
    VkShaderModuleCreateInfo smci = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = sizeof(fill_comp_spv), .pCode = fill_comp_spv };
    VkShaderModule sm;
    VKCHECK(vkCreateShaderModule(g_dev, &smci, NULL, &sm), "vkCreateShaderModule(comp)");
    VkComputePipelineCreateInfo cpi = { .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_COMPUTE_BIT, .module = sm, .pName = "main" },
        .layout = pl };
    VkPipeline cp;
    uint64_t t0 = now_us();
    VKCHECK(vkCreateComputePipelines(g_dev, VK_NULL_HANDLE, 1, &cpi, NULL, &cp), "vkCreateComputePipelines");
    say("  compute pipeline compiled in %.1f ms", (double)(now_us() - t0) / 1000.0);

    VkDescriptorPoolSize ps = { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1 };
    VkDescriptorPoolCreateInfo dpci = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = 1, .poolSizeCount = 1, .pPoolSizes = &ps };
    VkDescriptorPool dp;
    VKCHECK(vkCreateDescriptorPool(g_dev, &dpci, NULL, &dp), "vkCreateDescriptorPool");
    VkDescriptorSetAllocateInfo dsai = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, .descriptorPool = dp, .descriptorSetCount = 1, .pSetLayouts = &dsl };
    VkDescriptorSet ds;
    VKCHECK(vkAllocateDescriptorSets(g_dev, &dsai, &ds), "vkAllocateDescriptorSets");
    VkDescriptorBufferInfo dbi = { buf, 0, VK_WHOLE_SIZE };
    VkWriteDescriptorSet w = { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ds, .descriptorCount = 1,
                               .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &dbi };
    vkUpdateDescriptorSets(g_dev, 1, &w, 0, NULL);

    VkCommandBufferBeginInfo bi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
    VKCHECK(vkBeginCommandBuffer(g_cmd, &bi), "vkBeginCommandBuffer(compute)");
    vkCmdBindPipeline(g_cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cp);
    vkCmdBindDescriptorSets(g_cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &ds, 0, NULL);
    vkCmdDispatch(g_cmd, N / 64, 1, 1);
    VkMemoryBarrier mb = { .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT };
    vkCmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    VKCHECK(vkEndCommandBuffer(g_cmd), "vkEndCommandBuffer(compute)");
    VkSubmitInfo si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &g_cmd };
    VKCHECK(vkResetFences(g_dev, 1, &g_fence), "vkResetFences");
    VKCHECK(vkQueueSubmit(g_queue, 1, &si, g_fence), "vkQueueSubmit(compute)");
    VkResult wr = vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, 5000000000ull);
    if (wr != VK_SUCCESS) { say("  compute fence -> %d (GPU did not finish in 5 s)", (int)wr); return 0; }
    uint32_t bad = 0;
    for (uint32_t i = 0; i < N; i++)
        if (map[i] != i * 3u + 7u) bad++;
    say("  compute dispatch of %u invocations: %u wrong values", N, bad);
    vkResetCommandBuffer(g_cmd, 0);
    return bad == 0;
}

static int vk_present_setup(void)
{
    VkSurfaceCapabilitiesKHR caps;
    VKCHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g_phys, g_surface, &caps), "surface caps");
    uint32_t nf = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g_phys, g_surface, &nf, NULL);
    VkSurfaceFormatKHR fmts[16];
    if (nf > 16) nf = 16;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g_phys, g_surface, &nf, fmts);
    if (nf == 0) { say("  no surface formats"); return 0; }
    VkSurfaceFormatKHR fmt = fmts[0];
    g_extent = caps.currentExtent;
    if (g_extent.width == UINT32_MAX) { g_extent.width = 1920; g_extent.height = 1080; }
    say("  surface %ux%u format %d", g_extent.width, g_extent.height, (int)fmt.format);
    VkSwapchainCreateInfoKHR sci = { .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = g_surface,
        .minImageCount = caps.minImageCount < 2 ? 2 : caps.minImageCount, .imageFormat = fmt.format,
        .imageColorSpace = fmt.colorSpace, .imageExtent = g_extent, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform, .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE };
    VKCHECK(vkCreateSwapchainKHR(g_dev, &sci, NULL, &g_swap), "vkCreateSwapchainKHR");
    vkGetSwapchainImagesKHR(g_dev, g_swap, &g_nimg, NULL);
    VkImage imgs[8];
    if (g_nimg > 8) g_nimg = 8;
    vkGetSwapchainImagesKHR(g_dev, g_swap, &g_nimg, imgs);

    VkAttachmentDescription att = { .format = fmt.format, .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR };
    VkAttachmentReference ref = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
    VkSubpassDescription sub = { .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1, .pColorAttachments = &ref };
    VkRenderPassCreateInfo rpci = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1,
                                    .pAttachments = &att, .subpassCount = 1, .pSubpasses = &sub };
    VKCHECK(vkCreateRenderPass(g_dev, &rpci, NULL, &g_rp), "vkCreateRenderPass");
    g_fbs = calloc(g_nimg, sizeof(*g_fbs));
    for (uint32_t i = 0; i < g_nimg; i++) {
        VkImageViewCreateInfo ivci = { .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = imgs[i],
            .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = fmt.format, .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        VkImageView v;
        VKCHECK(vkCreateImageView(g_dev, &ivci, NULL, &v), "vkCreateImageView");
        VkFramebufferCreateInfo fbci = { .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = g_rp,
            .attachmentCount = 1, .pAttachments = &v, .width = g_extent.width, .height = g_extent.height, .layers = 1 };
        VKCHECK(vkCreateFramebuffer(g_dev, &fbci, NULL, &g_fbs[i]), "vkCreateFramebuffer");
    }

    VkShaderModuleCreateInfo vs_ci = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = sizeof(tri_vert_spv), .pCode = tri_vert_spv };
    VkShaderModuleCreateInfo fs_ci = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = sizeof(tri_frag_spv), .pCode = tri_frag_spv };
    VkShaderModule vs, fs;
    VKCHECK(vkCreateShaderModule(g_dev, &vs_ci, NULL, &vs), "vkCreateShaderModule(vert)");
    VKCHECK(vkCreateShaderModule(g_dev, &fs_ci, NULL, &fs), "vkCreateShaderModule(frag)");
    VkPipelineShaderStageCreateInfo st[2] = {
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vs, .pName = "main" },
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fs, .pName = "main" } };
    VkPipelineVertexInputStateCreateInfo vi = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
    VkPipelineInputAssemblyStateCreateInfo ia = { .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST };
    VkViewport vp = { 0, 0, (float)g_extent.width, (float)g_extent.height, 0, 1 };
    VkRect2D sc = { { 0, 0 }, g_extent };
    VkPipelineViewportStateCreateInfo vps = { .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, .viewportCount = 1, .pViewports = &vp, .scissorCount = 1, .pScissors = &sc };
    VkPipelineRasterizationStateCreateInfo rs = { .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE, .lineWidth = 1 };
    VkPipelineMultisampleStateCreateInfo ms = { .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT };
    VkPipelineColorBlendAttachmentState cba = { .colorWriteMask = 0xf };
    VkPipelineColorBlendStateCreateInfo cb = { .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, .attachmentCount = 1, .pAttachments = &cba };
    VkPipelineLayoutCreateInfo plci = { .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
    VkPipelineLayout pl;
    VKCHECK(vkCreatePipelineLayout(g_dev, &plci, NULL, &pl), "vkCreatePipelineLayout");
    VkGraphicsPipelineCreateInfo gp = { .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 2, .pStages = st,
        .pVertexInputState = &vi, .pInputAssemblyState = &ia, .pViewportState = &vps, .pRasterizationState = &rs,
        .pMultisampleState = &ms, .pColorBlendState = &cb, .layout = pl, .renderPass = g_rp };
    uint64_t t0 = now_us();
    VKCHECK(vkCreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &gp, NULL, &g_pipe), "vkCreateGraphicsPipelines");
    say("  graphics pipeline compiled in %.1f ms", (double)(now_us() - t0) / 1000.0);
    VkSemaphoreCreateInfo sem = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
    VKCHECK(vkCreateSemaphore(g_dev, &sem, NULL, &g_acq), "vkCreateSemaphore");
    VKCHECK(vkCreateSemaphore(g_dev, &sem, NULL, &g_ren), "vkCreateSemaphore");
    return 1;
}

static int vk_frame(float r, float g, float b)
{
    uint32_t idx;
    VkResult res = vkAcquireNextImageKHR(g_dev, g_swap, 2000000000ull, g_acq, VK_NULL_HANDLE, &idx);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) { say("  acquire -> %d", (int)res); return 0; }
    vkResetCommandBuffer(g_cmd, 0);
    VkCommandBufferBeginInfo bi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
    vkBeginCommandBuffer(g_cmd, &bi);
    VkClearValue clear = { .color = { .float32 = { r, g, b, 1 } } };
    VkRenderPassBeginInfo rpbi = { .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = g_rp,
        .framebuffer = g_fbs[idx], .renderArea = { { 0, 0 }, g_extent }, .clearValueCount = 1, .pClearValues = &clear };
    vkCmdBeginRenderPass(g_cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(g_cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pipe);
    vkCmdDraw(g_cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(g_cmd);
    vkEndCommandBuffer(g_cmd);
    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = 1, .pWaitSemaphores = &g_acq,
        .pWaitDstStageMask = &wait, .commandBufferCount = 1, .pCommandBuffers = &g_cmd,
        .signalSemaphoreCount = 1, .pSignalSemaphores = &g_ren };
    vkResetFences(g_dev, 1, &g_fence);
    res = vkQueueSubmit(g_queue, 1, &si, g_fence);
    if (res != VK_SUCCESS) { say("  submit -> %d", (int)res); return 0; }
    VkPresentInfoKHR pi = { .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1, .pWaitSemaphores = &g_ren,
        .swapchainCount = 1, .pSwapchains = &g_swap, .pImageIndices = &idx };
    res = vkQueuePresentKHR(g_queue, &pi);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) { say("  present -> %d", (int)res); return 0; }
    res = vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, 2000000000ull);
    if (res != VK_SUCCESS) { say("  frame fence -> %d", (int)res); return 0; }
    return 1;
}

static void st_vulkan(void)
{
    r_vk = 0;
    if (!vk_init()) return;
    r_vk_compute = vk_compute();
    if (!vk_present_setup()) { r_vk_present = 0; return; }
    uint64_t t0 = now_us();
    int n = 0;
    for (; n < 180; n++)
        if (!vk_frame(0.1f, 0.1f, 0.3f)) break;
    double s = (double)(now_us() - t0) / 1e6;
    r_vk_present = n == 180;
    r_vk_fps = n / (s > 0 ? s : 1);
    say("VULKAN: %d frames presented in %.2f s (%.1f fps)", n, s, r_vk_fps);
}

static void st_postvk(void)
{
    r_direct_post = direct_ladder(0);
    r_post_done = 1;
    say("POSTVK: %s of direct memory still usable with the GPU driver up", mib(r_direct_post));
}

static void st_fault(void)
{
    void *res = NULL;
    int rc = k_reserve(&res, 16 * MiB, 0, 2 * MiB);
    if (rc) { say("  reserve failed 0x%08x", rc); r_fault = 0; return; }
    const uint8_t *target = (const uint8_t *)res + 4 * MiB + 0x123;
    g_fault_hits = 0;
    g_fault_expect = 1;
    say("  loading from %p, which is reserved but not mapped", target);
    int got = probe_fault_load(target);
    g_fault_expect = 0;
    say("  returned 0x%x after %d handler entries, fault address reported 0x%lx", got, g_fault_hits,
        (unsigned long)g_fault_addr);
    r_fault = got == 0xFA57 && g_fault_hits == 1;
    if (r_fault) {
        uint64_t t0 = now_us();
        for (int i = 0; i < 2000; i++) {
            g_fault_hits = 0;
            g_fault_expect = 1;
            probe_fault_load(target);
        }
        g_fault_expect = 0;
        r_fault_us = (double)(now_us() - t0) / 2000.0;
        say("  2000 faults: %.2f us each", r_fault_us);
    }
    say("FAULT: %s", r_fault ? "handler can rewrite the context and resume (fastmem-style recovery works)"
                             : "resume by rewriting the context did NOT work");
    k_munmap(res, 16 * MiB);
}

/* ------------------------------------------------------------------ verdict */
static const char *yn(int v) { return v < 0 ? "not measured" : v ? "yes" : "NO"; }

static int verdict(void)
{
    say("================================ SUMMARY ================================");
    if (g_crashed_before) say("stages that crashed or hung on earlier launches: %s", g_crashed_list);
    say("Neo mode                       : %d", r_neo);
    say("direct memory (before GPU)     : %s", r_direct_done ? mib(r_direct_pre) : "not measured");
    say("direct memory (GPU driver up)  : %s", r_post_done ? mib(r_direct_post) : "not measured");
    say("flexible memory                : %s", r_flex_done ? mib(r_flex) : "not measured");
    say("largest VA reservation         : %lu GiB", (unsigned long)(r_va_max / GiB));
    say("double mapping / MAP_FIXED     : %s / %s", yn(r_alias), yn(r_fixed));
    say("JIT (RWX runs)                 : %s", yn(r_jit));
    say("JIT 128 MiB / 512 MiB          : %s / %s", yn(r_jit128), yn(r_jit512));
    say("JIT near eboot (rel32)         : %s", yn(r_jit_near));
    say("JIT RX narrow / widen          : %s / %s", yn(r_rx_narrow), yn(r_rx_widen));
    say("cores at full speed            : %d", r_cores_ok);
    say("single-thread mul-add rate     : %.0f M/s", r_mops_single);
    say("Vulkan device                  : %s (API %u.%u)", yn(r_vk), VK_VERSION_MAJOR(r_vk_api), VK_VERSION_MINOR(r_vk_api));
    say("Vulkan compute / present       : %s / %s (%.1f fps)", yn(r_vk_compute), yn(r_vk_present), r_vk_fps);
    say("Vulkan device-local / host     : %s / %s", mib(r_vk_local), mib(r_vk_host));
    say("fault resume (fastmem)         : %s (%.2f us)", yn(r_fault), r_fault_us);

    /* An emulated Switch has 4 GiB; Eden takes the guest RAM, a page table and its own heap. */
    uint64_t mem = r_post_done ? r_direct_post : r_direct_pre;
    int red = r_jit == 0 || r_vk == 0 || r_vk_compute == 0 || (r_direct_done && r_direct_pre < 2 * GiB);
    int green = !red && r_jit == 1 && r_vk == 1 && r_vk_compute == 1 && r_vk_present == 1 &&
                (r_post_done || r_direct_done) && mem >= 4 * GiB && !g_crashed_before;
    int v = red ? 2 : green ? 0 : 1;
    say("VERDICT: %s", v == 0 ? "GREEN - nothing blocks an Eden port"
                     : v == 1 ? "AMBER - possible, with the limits above"
                              : "RED - a blocker, see the lines marked NO");
    say("=========================================================================");
    return v;
}

int main(void)
{
    g_t0 = now_us();
    k_mkdir(DIR_PATH, 0777);
    g_log = k_open(LOG_PATH, KO_WRONLY | KO_CREAT | KO_APPEND, 0666);
    freopen(DRV_PATH, "a", stdout);
    freopen(DRV_PATH, "a", stderr);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    prog_load();
    prog_mark("R", "launch");
    say("");
    say("#################### eden-probe 1 - launch ####################");

    k_install_exc(XSIGSEGV, exc_handler);
    k_install_exc(XSIGBUS, exc_handler);
    k_install_exc(XSIGILL, exc_handler);
    k_install_exc(XSIGFPE, exc_handler);

    pthread_t wd;
    pthread_create(&wd, NULL, watchdog, NULL);

    if (stage_begin("INFO", 15, "system"))                                    { st_info();    stage_end("INFO"); }
    if (stage_begin("DIRECT", 90, "direct memory ceiling"))                  { st_direct();  stage_end("DIRECT"); }
    if (stage_begin("FLEX", 60, "flexible memory ceiling"))                  { st_flex();    stage_end("FLEX"); }
    if (stage_begin("VA", 30, "address-space reservations"))                 { st_va();      stage_end("VA"); }
    if (stage_begin("ALIAS", 30, "double mapping and MAP_FIXED"))            { st_alias();   stage_end("ALIAS"); }
    if (stage_begin("VMAP", 15, "address-space layout"))                     { st_vmap();    stage_end("VMAP"); }
    if (stage_begin("JIT", 20, "generated code"))                            { st_jit();     stage_end("JIT"); }
    if (stage_begin("JITBIG", 30, "large and near code caches"))             { st_jitbig();  stage_end("JITBIG"); }
    if (stage_begin("JITRX", 20, "narrowing and widening a code cache"))     { st_jitrx();   stage_end("JITRX"); }
    if (stage_begin("THREADS", 30, "cores"))                                 { st_threads(); stage_end("THREADS"); }
    if (stage_begin("VULKAN", 180, "RADV"))                                  { st_vulkan();  stage_end("VULKAN"); }
    if (stage_begin("POSTVK", 90, "direct memory with the GPU driver up"))   { st_postvk();  stage_end("POSTVK"); }
    if (stage_begin("FAULT", 20, "resuming after a page fault"))             { st_fault();   stage_end("FAULT"); }

    int v = verdict();
    prog_mark("DONE", "all");
    say("All stages ran. You can close the app; send /data/edenprobe/probe.log and driver.log.");

    float cr = v == 0 ? 0.0f : v == 1 ? 0.8f : 0.8f;
    float cg = v == 0 ? 0.6f : v == 1 ? 0.5f : 0.0f;
    float cb = 0.0f;
    if (r_vk_present == 1) {
        for (;;)
            if (!vk_frame(cr, cg, cb)) break;
    }
    for (;;) k_usleep(1000 * 1000);
}
