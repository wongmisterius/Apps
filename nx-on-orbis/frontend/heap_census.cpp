// SPDX-License-Identifier: GPL-3.0-or-later
// eden-ps4: who holds Eden's big heap allocations.
//
// The link wraps malloc/calloc/realloc/free/posix_memalign/aligned_alloc (-Wl,--wrap=...). Every
// allocation of 1 MiB or more is recorded with the three return addresses above the allocator
// (the first one inside Eden, past operator new and std:: containers), and Ps4HeapCensus() logs
// the call sites holding the most live bytes. Small allocations pass straight through.

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

extern "C" {
void* __real_malloc(size_t);
void* __real_calloc(size_t, size_t);
void* __real_realloc(void*, size_t);
void __real_free(void*);
int __real_posix_memalign(void**, size_t, size_t);
void* __real_aligned_alloc(size_t, size_t);
}

namespace Ps4 {
void Log(const char* fmt, ...);
}

namespace {

constexpr size_t Threshold = 1 << 20;
constexpr int MaxLive = 8192;
constexpr std::uint64_t ImageBase = 0x400000;

struct Live {
    void* ptr;
    size_t size;
    std::uint64_t site[3];
};
Live g_live[MaxLive];
int g_count = 0;
std::atomic_flag g_lock = ATOMIC_FLAG_INIT;
std::atomic<std::uint64_t> g_untracked{0};

struct Guard {
    Guard() {
        while (g_lock.test_and_set(std::memory_order_acquire)) {
        }
    }
    ~Guard() {
        g_lock.clear(std::memory_order_release);
    }
};

__attribute__((noinline)) void Sites(std::uint64_t out[3]) {
    // Frames: Sites <- Track <- __wrap_x <- (operator new / caller) <- ...; keep the 3 above wrap.
    auto* rbp = static_cast<std::uint64_t*>(__builtin_frame_address(0));
    std::uint64_t ret[6] = {};
    for (int i = 0; i < 6 && rbp != nullptr; ++i) {
        ret[i] = rbp[1];
        auto* next = reinterpret_cast<std::uint64_t*>(rbp[0]);
        if (next <= rbp || reinterpret_cast<std::uintptr_t>(next) - reinterpret_cast<std::uintptr_t>(rbp) > (1u << 20)) {
            break;
        }
        rbp = next;
    }
    out[0] = ret[2];
    out[1] = ret[3];
    out[2] = ret[4];
}

__attribute__((noinline)) void Track(void* ptr, size_t size) {
    if (ptr == nullptr || size < Threshold) {
        return;
    }
    std::uint64_t site[3];
    Sites(site);
    Guard g;
    if (g_count < MaxLive) {
        g_live[g_count++] = {ptr, size, {site[0], site[1], site[2]}};
    } else {
        g_untracked.fetch_add(size);
    }
}

void Untrack(void* ptr) {
    if (ptr == nullptr) {
        return;
    }
    Guard g;
    for (int i = g_count - 1; i >= 0; --i) {
        if (g_live[i].ptr == ptr) {
            g_live[i] = g_live[--g_count];
            return;
        }
    }
}

} // Anonymous namespace

extern "C" {

void* __wrap_malloc(size_t size) {
    void* p = __real_malloc(size);
    Track(p, size);
    return p;
}

void* __wrap_calloc(size_t n, size_t size) {
    void* p = __real_calloc(n, size);
    Track(p, n * size);
    return p;
}

void* __wrap_realloc(void* old, size_t size) {
    Untrack(old);
    void* p = __real_realloc(old, size);
    Track(p, size);
    return p;
}

void __wrap_free(void* p) {
    Untrack(p);
    __real_free(p);
}

int __wrap_posix_memalign(void** out, size_t align, size_t size) {
    const int rc = __real_posix_memalign(out, align, size);
    if (rc == 0) {
        Track(*out, size);
    }
    return rc;
}

void* __wrap_aligned_alloc(size_t align, size_t size) {
    void* p = __real_aligned_alloc(align, size);
    Track(p, size);
    return p;
}

/// Logs the call sites holding the most live bytes in allocations of 1 MiB or more.
void Ps4HeapCensus() {
    struct Site {
        std::uint64_t site[3];
        size_t bytes;
        int count;
    };
    static Site sites[256];
    int nsites = 0;
    size_t total = 0;
    {
        Guard g;
        for (int i = 0; i < g_count; ++i) {
            total += g_live[i].size;
            int s = 0;
            for (; s < nsites; ++s) {
                if (std::memcmp(sites[s].site, g_live[i].site, sizeof(sites[s].site)) == 0) {
                    break;
                }
            }
            if (s == nsites) {
                if (nsites == 256) {
                    continue;
                }
                std::memcpy(sites[nsites].site, g_live[i].site, sizeof(sites[s].site));
                sites[nsites].bytes = 0;
                sites[nsites].count = 0;
                ++nsites;
            }
            sites[s].bytes += g_live[i].size;
            sites[s].count += 1;
        }
    }
    Ps4::Log("heap census: %lu MiB live in allocations >= 1 MiB, %d sites (untracked overflow %lu MiB)",
             static_cast<unsigned long>(total >> 20), nsites,
             static_cast<unsigned long>(g_untracked.load() >> 20));
    for (int shown = 0; shown < 12; ++shown) {
        int best = -1;
        for (int s = 0; s < nsites; ++s) {
            if (sites[s].bytes != 0 && (best < 0 || sites[s].bytes > sites[best].bytes)) {
                best = s;
            }
        }
        if (best < 0) {
            break;
        }
        Ps4::Log("   %5lu MiB in %4d from eboot+0x%lx <- 0x%lx <- 0x%lx",
                 static_cast<unsigned long>(sites[best].bytes >> 20), sites[best].count,
                 static_cast<unsigned long>(sites[best].site[0] - ImageBase),
                 static_cast<unsigned long>(sites[best].site[1] - ImageBase),
                 static_cast<unsigned long>(sites[best].site[2] - ImageBase));
        sites[best].bytes = 0;
    }
}

} // extern "C"
