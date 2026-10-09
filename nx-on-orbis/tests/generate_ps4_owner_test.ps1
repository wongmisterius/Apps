$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$cachePath = Join-Path $base '..\deps\eden\src\video_core\texture_cache\texture_cache.h'
$imagePath = Join-Path $base '..\deps\eden\src\video_core\texture_cache\image_base.h'
$cacheSource = [IO.File]::ReadAllText($cachePath)
$imageSource = [IO.File]::ReadAllText($imagePath)
$methods = @()
foreach ($name in @('RegisterImage', 'UnregisterImage')) {
    $pattern = '(?ms)^template <class P>\r?\nvoid TextureCache<P>::' + $name + '\(ImageId image_id\) \{.*?^\}'
    $matches = [regex]::Matches($cacheSource, $pattern)
    if ($matches.Count -ne 1) { throw "Expected one real $name method" }
    $methods += $matches[0].Value.Replace("template <class P>`r`n", '').Replace("template <class P>`n", '').Replace('TextureCache<P>::', 'TextureCache::')
}
$ownerMatch = [regex]::Match($imageSource, '(?m)^\s*size_t registered_gpu_map = .*?;')
if (-not $ownerMatch.Success) { throw 'Missing real image owner declaration' }
$enumMatch = [regex]::Match($imageSource, '(?ms)^enum class ImageFlagBits : u32 \{.*?^\};')
if (-not $enumMatch.Success) { throw 'Missing real flags declaration' }
$prefix = @'
// GENERATED: actual RegisterImage/UnregisterImage bodies and owner declaration from staged source.
// Only surrounding cache/backend services are fixtures; ownership logic is not replicated.
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <limits>
#include <optional>
#include <unordered_map>
#include <vector>
#define __ORBIS__ 1
using u64 = std::uint64_t;
using u32 = std::uint32_t;
using GPUVAddr = u64;
using DAddr = u64;
using ImageId = u32;
using ImageViewId = u32;
using ImageMapId = u32;
int assertion_count = 0;
int owner_log_count = 0;
#define ASSERT_MSG(condition, ...) do { if (!(condition)) ++assertion_count; } while (0)
#define ASSERT(condition) ASSERT_MSG(condition)
#define LOG_WARNING(...) do { ++owner_log_count; } while (0)
namespace Common {
template<class T> using IdentityHash = std::hash<T>;
template<class K, class V, class H = std::hash<K>> using unordered_map = std::unordered_map<K, V, H>;
u64 AlignUp(u64 value, u64 alignment) { return (value + alignment - 1) & ~(alignment - 1); }
}
namespace boost::container { template<class T, size_t N> using small_vector = std::vector<T>; }
@FLAGS@
ImageFlagBits operator&(ImageFlagBits a, ImageFlagBits b) { return ImageFlagBits(u32(a) & u32(b)); }
ImageFlagBits operator~(ImageFlagBits a) { return ImageFlagBits(~u32(a)); }
ImageFlagBits& operator|=(ImageFlagBits& a, ImageFlagBits b) { a = ImageFlagBits(u32(a) | u32(b)); return a; }
ImageFlagBits& operator&=(ImageFlagBits& a, ImageFlagBits b) { a = a & b; return a; }
bool True(ImageFlagBits value) { return u32(value) != 0; }
bool False(ImageFlagBits value) { return !True(value); }
bool IsPixelFormatASTC(u32) { return false; }
u64 TranscodedAstcSize(u64 size, u32) { return size; }
struct ImageInfo { u32 format = 0; };
struct ImageBase {
    @OWNER@
    ImageFlagBits flags = ImageFlagBits::CpuModified;
    ImageInfo info;
    u64 gpu_addr = 0, cpu_addr = 0, guest_size_bytes = 0, unswizzled_size_bytes = 0;
    size_t lru_index = std::numeric_limits<size_t>::max();
    ImageMapId map_view_id = 0;
};
using Image = ImageBase;
struct ImageMapView { u64 cpu_addr, size; ImageId image_id; bool picked = false; };
struct MapSlots {
    std::unordered_map<ImageMapId, ImageMapView> values;
    ImageMapId next = 1;
    ImageMapId insert(u64, u64 cpu, u64 size, ImageId image) {
        auto id = next++; values.emplace(id, ImageMapView{cpu, size, image}); return id;
    }
    ImageMapView& operator[](ImageMapId id) { return values.at(id); }
    void erase(ImageMapId id) { assert(values.erase(id) == 1); }
};
struct Lru {
    size_t next = 0, inserts = 0, frees = 0;
    std::unordered_map<size_t, bool> live;
    size_t Insert(ImageId, u64) { auto id = next++; live[id] = true; ++inserts; return id; }
    void Free(size_t id) { assert(live.at(id)); live.at(id) = false; ++frees; }
};
using GpuMap = Common::unordered_map<u64, std::vector<ImageId>, Common::IdentityHash<u64>>;
struct Channel { GpuMap* gpu_page_table; GpuMap* sparse_page_table; };
struct TextureCache {
    static constexpr u64 YUZU_PAGEBITS = 20;
    std::unordered_map<ImageId, Image> slot_images;
    MapSlots slot_map_views;
    Lru lru_cache;
    std::deque<GpuMap> gpu_page_table_storage = std::deque<GpuMap>(4);
    std::unordered_map<ImageId, std::vector<ImageMapId>> sparse_views;
    std::unordered_map<u64, std::vector<ImageMapId>> page_table;
    std::unordered_map<size_t, size_t> address_spaces{{100, 0}, {200, 1}};
    Channel channel_a{&gpu_page_table_storage[0], &gpu_page_table_storage[1]};
    Channel channel_b{&gpu_page_table_storage[2], &gpu_page_table_storage[3]};
    Channel channel_a_second{&gpu_page_table_storage[0], &gpu_page_table_storage[1]};
    Channel* channel_state = &channel_a;
    size_t current_address_space = 100;
    u64 total_used_memory = 0, frame_tick = 0;
    std::optional<size_t> getStorageID(size_t id) const {
        auto it = address_spaces.find(id);
        return it == address_spaces.end() ? std::nullopt : std::optional<size_t>(it->second);
    }
    template<class F> void ForEachGPUPage(u64 addr, size_t size, F f) {
        for (u64 p = addr >> 20, end = (addr + size - 1) >> 20; p <= end; ++p) f(p);
    }
    template<class F> void ForEachCPUPage(u64 addr, size_t size, F f) { ForEachGPUPage(addr, size, f); }
    template<class F> void ForEachSparseSegment(const Image& image, F f) {
        const size_t half = image.guest_size_bytes / 2;
        f(image.gpu_addr, image.cpu_addr, half);
        f(image.gpu_addr + half, image.cpu_addr + half + (1ULL << 20), half);
    }
    void RegisterImage(ImageId);
    void UnregisterImage(ImageId);
    void BindB() { current_address_space = 200; channel_state = &channel_b; }
    void BindA() { current_address_space = 100; channel_state = &channel_a; }
    void SetImage(ImageId id, u64 gpu, u64 cpu, bool sparse) {
        Image image;
        image.gpu_addr = gpu; image.cpu_addr = cpu;
        image.guest_size_bytes = 2ULL << 20;
        image.unswizzled_size_bytes = image.guest_size_bytes;
        if (sparse) image.flags |= ImageFlagBits::Sparse;
        slot_images[id] = image;
    }
};
'@
$suffix = @'
size_t CountImage(const GpuMap& pages, ImageId image) {
    size_t count = 0;
    for (const auto& [page, values] : pages) count += std::count(values.begin(), values.end(), image);
    return count;
}
void TestDualAs(bool sparse) {
    TextureCache cache;
    cache.SetImage(1, 0x1000000, 0x4000000, sparse);
    cache.RegisterImage(1);
    assert(cache.slot_images.at(1).registered_gpu_map == 0);
    cache.BindB();
    cache.SetImage(2, 0x1000000, 0x8000000, sparse); // identical GPU VA, different AS/CPU backing
    cache.RegisterImage(2);
    const auto a_before = CountImage(cache.gpu_page_table_storage[0], 1);
    const auto b_before = CountImage(cache.gpu_page_table_storage[2], 2);
    assert(a_before == 2 && b_before == 2);
    int assertions_before = assertion_count;
    cache.UnregisterImage(1); // owner A while active B
    assert(assertion_count == assertions_before);
    assert(CountImage(cache.gpu_page_table_storage[0], 1) == 0);
    assert(CountImage(cache.gpu_page_table_storage[1], 1) == 0);
    assert(CountImage(cache.gpu_page_table_storage[2], 2) == b_before);
    if (sparse) assert(CountImage(cache.gpu_page_table_storage[3], 2) == 2);
    assert(!True(cache.slot_images.at(1).flags & ImageFlagBits::Registered));
    assert(cache.lru_cache.frees == 1);
    cache.SetImage(1, 0x1000000, 0xc000000, sparse); // reuse the image's old slot in B
    cache.RegisterImage(1);
    assert(CountImage(cache.gpu_page_table_storage[0], 1) == 0);
    assert(CountImage(cache.gpu_page_table_storage[2], 1) == 2);
    cache.BindA();
    cache.UnregisterImage(1); // owner B while active A
    assert(CountImage(cache.gpu_page_table_storage[2], 1) == 0);
    assert(CountImage(cache.gpu_page_table_storage[2], 2) == b_before);
    cache.UnregisterImage(2);
    assert(cache.slot_map_views.values.empty() && cache.sparse_views.empty());
    for (const auto& [page, maps] : cache.page_table) assert(maps.empty());
}
int main() {
    TestDualAs(false);
    TestDualAs(true);
    TextureCache cache;
    cache.SetImage(3, 0x2000000, 0x4000000, false);
    cache.RegisterImage(3);
    cache.channel_state = &cache.channel_a_second; // same AS, different channel
    const int before = owner_log_count;
    cache.UnregisterImage(3);
    assert(owner_log_count == before);
    assert(CountImage(cache.gpu_page_table_storage[0], 3) == 0);
    cache.SetImage(4, 0x3000000, 0x5000000, false);
    cache.current_address_space = 999; // invalid registration leaves state/LRU untouched
    size_t inserts = cache.lru_cache.inserts;
    cache.RegisterImage(4);
    assert(cache.lru_cache.inserts == inserts);
    assert(!True(cache.slot_images.at(4).flags & ImageFlagBits::Registered));
    cache.BindA();
    cache.RegisterImage(4);
    cache.slot_images.at(4).registered_gpu_map = std::numeric_limits<size_t>::max();
    size_t frees = cache.lru_cache.frees;
    cache.UnregisterImage(4); // invalid owner validated before flag/LRU mutations
    assert(cache.lru_cache.frees == frees);
    assert(True(cache.slot_images.at(4).flags & ImageFlagBits::Registered));
    cache.slot_images.at(4).registered_gpu_map = 0;
    cache.UnregisterImage(4);
    cache.RegisterImage(4);
    cache.BindB();
    inserts = cache.lru_cache.inserts;
    cache.RegisterImage(4); // duplicate registration does not migrate its owner
    assert(cache.lru_cache.inserts == inserts && cache.slot_images.at(4).registered_gpu_map == 0);
    cache.UnregisterImage(4);
    frees = cache.lru_cache.frees;
    cache.UnregisterImage(4); // double-unregister retains existing diagnostic and guard
    assert(cache.lru_cache.frees == frees);
    // Diagnostics cap: perform further cross-AS deletions using real methods.
    for (u32 id = 10; id != 40; ++id) {
        cache.BindA(); cache.SetImage(id, u64(id) << 22, u64(id) << 24, false);
        cache.RegisterImage(id); cache.BindB(); cache.UnregisterImage(id);
    }
    assert(owner_log_count == 16);
    assert(assertion_count == 4); // invalid AS, invalid owner, duplicate register, duplicate unregister
    std::puts("PASS: extracted real registration methods; dual AS normal/sparse, slot reuse, same AS channels, invalid owner, duplicate guards, log cap");
}
'@
$prefix = $prefix.Replace('@FLAGS@', $enumMatch.Value).Replace('@OWNER@', $ownerMatch.Value.Trim())
$result = $prefix + "`n" + ($methods -join "`n`n") + "`n" + $suffix
[IO.File]::WriteAllText((Join-Path $base 'ps4_owner_test.cpp'), $result, [Text.UTF8Encoding]::new($false))
Write-Output 'Generated ps4_owner_test.cpp from the actual project methods and ImageBase declaration.'
