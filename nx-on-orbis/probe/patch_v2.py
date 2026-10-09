import re

p = 'probe.c'
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert old in s, old[:120]
    s = s.replace(old, new, 1)


# drop libkernel imports nobody has proven on this console
for name in ['k_is_neo', 'k_cpu_freq', 'k_tsc_freq', 'k_sw_version', 'k_open', 'k_write', 'k_read',
             'k_close', 'k_fsync', 'k_mkdir', 'k_pthread_self', 'k_pthread_setaffinity', 'k_current_cpu']:
    s, n = re.subn(r'extern [^\n]*\b' + name + r'\([^\n]*\n', '', s)
    assert n == 1, name
s = re.sub(r'#define KO_[A-Z]+ +0x[0-9]+\n', '', s)
rep('#include <unistd.h>\n', '#include <unistd.h>\n#include <sys/stat.h>\n')

# logging through stdio, the path SoH and Majora's already use on this console
rep('''#define DIR_PATH  "/data/edenprobe"
#define LOG_PATH  DIR_PATH "/probe.log"
#define PROG_PATH DIR_PATH "/progress.txt"
#define DRV_PATH  DIR_PATH "/driver.log"

static int g_log = -1;''', '''/* /data/edenprobe/<variant>/, the variant read from /app0/variant.txt so one eboot serves
 * every package of a round. */
static char g_dir[64] = "/data/edenprobe";
static char LOG_PATH[96], PROG_PATH[96], DRV_PATH[96];

static FILE *g_log;''')
rep('''    k_debug_out(0, buf);
    if (g_log >= 0) {
        k_write(g_log, buf, n);
        k_fsync(g_log);
    }''', '''    k_debug_out(0, buf);
    if (g_log) {
        fwrite(buf, 1, n, g_log);
        fflush(g_log);
        fsync(fileno(g_log));
    }''')
rep('''static int  g_prog_fd = -1;

static void prog_load(void)
{
    int fd = k_open(PROG_PATH, KO_RDONLY, 0);
    if (fd >= 0) {
        int64_t n = k_read(fd, g_prog, sizeof(g_prog) - 1);
        g_prog[n > 0 ? n : 0] = 0;
        k_close(fd);
    }''', '''static FILE *g_prog_f;

static void prog_load(void)
{
    FILE *f = fopen(PROG_PATH, "r");
    if (f) {
        size_t n = fread(g_prog, 1, sizeof(g_prog) - 1, f);
        g_prog[n] = 0;
        fclose(f);
    }''')
rep('''    g_prog_fd = k_open(PROG_PATH, KO_WRONLY | KO_CREAT | (fresh ? KO_TRUNC : KO_APPEND), 0666);''',
    '''    g_prog_f = fopen(PROG_PATH, fresh ? "w" : "a");''')
rep('''    if (g_prog_fd >= 0) {
        k_write(g_prog_fd, line, n);
        k_fsync(g_prog_fd);
    }''', '''    if (g_prog_f) {
        fwrite(line, 1, n, g_prog_f);
        fflush(g_prog_f);
        fsync(fileno(g_prog_f));
    }''')

# INFO without the unproven calls: clocks measured instead of asked for
rep('''    struct { uint64_t size; char str[0x1c]; uint32_t hex; } ver;
    memset(&ver, 0, sizeof(ver));
    ver.size = sizeof(ver);
    int rc = k_sw_version(&ver);
    say("firmware: %s (0x%08x) rc=0x%08x", rc == 0 ? ver.str : "?", ver.hex, rc);

    r_neo = k_is_neo();
    say("Neo (PS4 Pro) mode: %d", r_neo);
    say("CPU frequency: %.0f MHz, TSC %.0f MHz", (double)k_cpu_freq() / 1e6, (double)k_tsc_freq() / 1e6);
''', '''    int rc;
    /* TSC rate against the process clock, then the core clock from a chain of dependent adds
     * (one per cycle on Jaguar). Asking the kernel for these took imports nobody had proven here. */
    uint64_t t0 = now_us(), c0 = rdtsc();
    while (now_us() - t0 < 200000) { }
    double tsc_mhz = (double)(rdtsc() - c0) / (double)(now_us() - t0);
    uint64_t n = 200000000ull, x = 0;
    t0 = now_us();
    __asm__ volatile("1: addq $1, %0\\n\\taddq $1, %0\\n\\taddq $1, %0\\n\\taddq $1, %0\\n\\tsubq $4, %1\\n\\tjnz 1b"
                     : "+r"(x), "+r"(n));
    double core_mhz = 200000000.0 / (double)(now_us() - t0);
    say("TSC %.0f MHz; core clock measured at about %.0f MHz (PS4 base 1600, Pro/boost 2130)", tsc_mhz, core_mhz);
''')
rep('static uint64_t now_us(void) { return k_process_time(); }', '''static uint64_t now_us(void) { return k_process_time(); }
static uint64_t rdtsc(void)
{
    uint32_t lo, hi;
    __asm__ volatile("rdtsc" : "=a"(lo), "=d"(hi));
    return ((uint64_t)hi << 32) | lo;
}''')

# THREADS without pinning: eight free threads, and how much each gets
rep('''    if (a->core >= 0) a->rc = k_pthread_setaffinity(k_pthread_self(), 1ull << a->core);
    k_usleep(2000);
    a->seen = k_current_cpu();''', '''    k_usleep(2000);''')
rep('''    say("  one thread alone: %.0f M dependent mul-adds/s (on cpu %d)", r_mops_single, one.seen);''',
    '''    say("  one thread alone: %.0f M dependent mul-adds/s", r_mops_single);''')
rep('''        say("  core %d: affinity rc=0x%08x, ran on cpu %d, %.0f M/s (%.0f%% of alone)",
            i, a[i].rc, a[i].seen, m, 100.0 * m / (r_mops_single > 0 ? r_mops_single : 1));
        if (a[i].rc == 0 && m > 0.6 * r_mops_single) ok++;''', '''        say("  thread %d of 8 together: %.0f M/s (%.0f%% of alone)",
            i, m, 100.0 * m / (r_mops_single > 0 ? r_mops_single : 1));
        if (m > 0.6 * r_mops_single) ok++;''')
rep('''    say("THREADS: %d cores ran at more than 60%% of a lone thread's speed", ok);''',
    '''    say("THREADS: %d of 8 simultaneous threads ran at more than 60%% of a lone thread's speed", ok);''')
rep('    say("Neo mode                       : %d", r_neo);\n', '')
rep('r_fault = -1, r_neo = -1;', 'r_fault = -1;')
rep('''    say("cores at full speed            : %d", r_cores_ok);''',
    '''    say("threads at full speed (of 8)   : %d", r_cores_ok);''')

# main: pick the variant, create the folder with mkdir, first line before anything else
rep('''    g_t0 = now_us();
    k_mkdir(DIR_PATH, 0777);
    g_log = k_open(LOG_PATH, KO_WRONLY | KO_CREAT | KO_APPEND, 0666);
    freopen(DRV_PATH, "a", stdout);''', '''    g_t0 = now_us();
    mkdir("/data/edenprobe", 0777);
    char variant[16] = "";
    FILE *vf = fopen("/app0/variant.txt", "r");
    if (vf) {
        if (fgets(variant, sizeof(variant), vf)) variant[strcspn(variant, "\\r\\n ")] = 0;
        fclose(vf);
    }
    if (variant[0]) snprintf(g_dir, sizeof(g_dir), "/data/edenprobe/%s", variant);
    mkdir(g_dir, 0777);
    snprintf(LOG_PATH, sizeof(LOG_PATH), "%s/probe.log", g_dir);
    snprintf(PROG_PATH, sizeof(PROG_PATH), "%s/progress.txt", g_dir);
    snprintf(DRV_PATH, sizeof(DRV_PATH), "%s/driver.log", g_dir);
    g_log = fopen(LOG_PATH, "a");
    if (g_log) setvbuf(g_log, NULL, _IONBF, 0);
    say("eden-probe 2 reached main (variant %s)", variant[0] ? variant : "-");
    freopen(DRV_PATH, "a", stdout);''')
rep('say("#################### eden-probe 1 - launch ####################");',
    'say("#################### eden-probe 2 - launch ####################");')
rep('say("All stages ran. You can close the app; send /data/edenprobe/probe.log and driver.log.");',
    'say("All stages ran. You can close the app; send %s/probe.log and driver.log.", g_dir);')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("ok")
