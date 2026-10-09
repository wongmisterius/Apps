// eden-ps4: checks boost::unordered_flat_map as Eden's texture cache uses it (u64 page -> vector of
// ids, Common::IdentityHash). Test 18 showed an entry pushed into a page and not found there in the
// same frame. Built for the PC (host check) and also run at startup on the console (main.cpp).
#include <cstdint>
#include <cstdio>
#include <vector>

#include <boost/unordered/unordered_flat_map.hpp>

namespace {
struct IdentityHash {
    std::size_t operator()(std::uint64_t value) const noexcept {
        return static_cast<std::size_t>(value);
    }
};
using Map = boost::unordered::unordered_flat_map<std::uint64_t, std::vector<std::uint32_t>, IdentityHash>;
} // namespace

/// Returns the number of (page, id) pairs pushed that cannot be found afterwards.
extern "C" int EdenPs4FlatMapCheck(char* report, int report_size) {
    Map map;
    std::vector<std::pair<std::uint64_t, std::uint32_t>> pushed;
    const auto push = [&](std::uint64_t page, std::uint32_t id) {
        map[page].push_back(id);
        pushed.emplace_back(page, id);
    };
    // The registrations of test 18's first frame (gpu address >> 20), in order.
    const struct {
        std::uint32_t id;
        std::uint64_t first, last;
    } frame0[] = {{1, 0x5583, 0x5586}, {3, 0x5586, 0x5589}, {2, 0x5250, 0x5254},
                  {7, 0x558b, 0x558b}, {6, 0x5580, 0x5582}, {5, 0x5589, 0x558b},
                  {4, 0x501d, 0x5025}};
    for (const auto& r : frame0) {
        for (std::uint64_t page = r.first; page <= r.last; ++page) {
            push(page, r.id);
        }
    }
    // Then many more, like a session's worth of textures across the GPU address space.
    std::uint64_t x = 0x9E3779B97F4A7C15ull;
    for (std::uint32_t id = 100; id < 20000; ++id) {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        const std::uint64_t page = 0x5000 + (x % 0x1000);
        const std::uint64_t pages = 1 + (x >> 40) % 8;
        for (std::uint64_t p = page; p < page + pages; ++p) {
            push(p, id);
        }
    }
    int missing = 0;
    std::uint64_t first_page = 0;
    std::uint32_t first_id = 0;
    for (const auto& [page, id] : pushed) {
        const auto it = map.find(page);
        bool found = false;
        if (it != map.end()) {
            for (const std::uint32_t v : it->second) {
                found |= v == id;
            }
        }
        if (!found) {
            if (missing == 0) {
                first_page = page;
                first_id = id;
            }
            ++missing;
        }
    }
    std::snprintf(report, static_cast<std::size_t>(report_size),
                  "%zu pairs in %zu pages, %d missing (first: page 0x%llx id %u)", pushed.size(),
                  map.size(), missing, static_cast<unsigned long long>(first_page), first_id);
    return missing;
}

#ifndef EDEN_PS4_NO_MAIN
int main() {
    char report[256];
    const int missing = EdenPs4FlatMapCheck(report, sizeof(report));
    std::printf("flat_map check: %s\n", report);
    return missing == 0 ? 0 : 1;
}
#endif
