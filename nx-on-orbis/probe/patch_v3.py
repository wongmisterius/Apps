p = 'probe.c'
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert old in s, old[:120]
    s = s.replace(old, new, 1)


# The driver and the overlay report through orbis_log, which drops everything until a sink is set.
rep('#include <vulkan/vulkan.h>\n', '''#include <vulkan/vulkan.h>
#include <orbis_log.h>
''')
rep('''static const char *mib(uint64_t bytes)''', '''static void say_v(const char *fmt, va_list ap)
{
    char buf[900];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    size_t n = strlen(buf);
    while (n && (buf[n - 1] == '\\n' || buf[n - 1] == '\\r')) buf[--n] = 0;
    say("  | %s", buf);
}

static void on_fatal(const char *what)
{
    say("!! FATAL reported by the driver/overlay: %s", what ? what : "(null)");
    say("!! The stage stays marked; the watchdog will close the app.");
    for (;;) k_usleep(1000 * 1000);
}

extern void probe_install_orbis_handlers(void);

static const char *mib(uint64_t bytes)''')
rep('''    prog_load();
    prog_mark("R", "launch");''', '''    orbis_set_log(say_v);
    orbis_set_log_fatal(say_v);
    orbis_set_fatal_action(on_fatal);

    prog_load();
    prog_mark("R", "launch");''')
rep('''    k_install_exc(XSIGSEGV, exc_handler);''', '''    probe_install_orbis_handlers();   /* SIGABRT and std::terminate; faults stay with ours */
    k_install_exc(XSIGSEGV, exc_handler);''')
rep('say("eden-probe 2 reached main (variant %s)"', 'say("eden-probe 3 reached main (variant %s)"')
rep('say("#################### eden-probe 2 - launch ####################");',
    'say("#################### eden-probe 3 - launch ####################");')

# The allocation ceiling moves out of vk_init: it ran right before the crash, so it gets a stage of
# its own at the end, after compute and present have had their turn.
rep('''    uint32_t tl = find_type(&mp, ~0u, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    uint32_t th = find_type(&mp, ~0u, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (tl != UINT32_MAX) { r_vk_local = alloc_ceiling(tl, 4 * GiB); say("  device-local allocations reached %s", mib(r_vk_local)); }
    if (th != UINT32_MAX) { r_vk_host = alloc_ceiling(th, 4 * GiB); say("  host-visible allocations reached %s", mib(r_vk_host)); }
''', '')

# Breadcrumbs through the compute test.
for old, label in [
    ('    VKCHECK(vkCreateDescriptorPool(g_dev, &dpci, NULL, &dp), "vkCreateDescriptorPool");', 'descriptor pool'),
    ('    VKCHECK(vkAllocateDescriptorSets(g_dev, &dsai, &ds), "vkAllocateDescriptorSets");', 'descriptor set'),
    ('    vkUpdateDescriptorSets(g_dev, 1, &w, 0, NULL);', 'descriptor write'),
    ('    VKCHECK(vkBeginCommandBuffer(g_cmd, &bi), "vkBeginCommandBuffer(compute)");', 'recording'),
    ('    VKCHECK(vkQueueSubmit(g_queue, 1, &si, g_fence), "vkQueueSubmit(compute)");', 'submit'),
    ('    VkResult wr = vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, 5000000000ull);', 'waiting for the GPU'),
]:
    rep(old, '    say("  compute: %s");\n' % label + old)
rep('''    VKCHECK(vkCreateBuffer(g_dev, &bci, NULL, &buf), "vkCreateBuffer");''',
    '''    say("  compute: buffer");
    VKCHECK(vkCreateBuffer(g_dev, &bci, NULL, &buf), "vkCreateBuffer");''')
rep('''    VkPipeline cp;
    uint64_t t0 = now_us();''', '''    VkPipeline cp;
    say("  compute: pipeline");
    uint64_t t0 = now_us();''')
rep('''    vkCmdDispatch(g_cmd, N / 64, 1, 1);''', '''    say("  compute: dispatch recorded at buffer %p", (void *)map);
    vkCmdDispatch(g_cmd, N / 64, 1, 1);''')

# VULKAN becomes four stages, so a crash in one no longer hides the others.
rep('''static void st_vulkan(void)
{
    r_vk = 0;
    if (!vk_init()) return;
    r_vk_compute = vk_compute();
    if (!vk_present_setup()) { r_vk_present = 0; return; }
    uint64_t t0 = now_us();''', '''static void st_vkinit(void)
{
    r_vk = 0;
    if (!vk_init()) r_vk = 0;
}

static void st_vkcompute(void)
{
    r_vk_compute = vk_compute();
    say("VKCOMPUTE: %s", r_vk_compute ? "compute results correct" : "compute FAILED");
}

static void st_vkalloc(void)
{
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(g_phys, &mp);
    uint32_t tl = find_type(&mp, ~0u, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    uint32_t th = find_type(&mp, ~0u, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (tl != UINT32_MAX) { r_vk_local = alloc_ceiling(tl, 4 * GiB); say("  device-local allocations reached %s", mib(r_vk_local)); }
    if (th != UINT32_MAX) { r_vk_host = alloc_ceiling(th, 4 * GiB); say("  host-visible allocations reached %s", mib(r_vk_host)); }
}

static void st_vulkan(void)
{
    if (!vk_present_setup()) { r_vk_present = 0; return; }
    uint64_t t0 = now_us();''')
rep('''    if (stage_begin("VULKAN", 180, "RADV"))                                  { st_vulkan();  stage_end("VULKAN"); }''',
    '''    if (stage_begin("VKINIT", 60, "RADV device, features, extensions"))     { st_vkinit();  stage_end("VKINIT"); }
    if (r_vk == 1 && stage_begin("VKPRESENT", 120, "pipeline and 180 frames on screen")) { st_vulkan(); stage_end("VKPRESENT"); }
    if (r_vk == 1 && stage_begin("VKCOMPUTE", 60, "a compute dispatch, checked")) { st_vkcompute(); stage_end("VKCOMPUTE"); }
    if (r_vk == 1 && stage_begin("VKALLOC", 60, "GPU memory ceiling"))          { st_vkalloc(); stage_end("VKALLOC"); }''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
