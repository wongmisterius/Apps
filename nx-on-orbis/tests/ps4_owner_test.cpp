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
enum class ImageFlagBits : u32 {
    AcceleratedUpload = 1 << 0, ///< Upload can be accelerated in the GPU
    Converted = 1 << 1,   ///< Guest format is not supported natively and it has to be converted
    CpuModified = 1 << 2, ///< Contents have been modified from the CPU
    GpuModified = 1 << 3, ///< Contents have been modified from the GPU
    Tracked = 1 << 4,     ///< Writes and reads are being hooked from the CPU JIT
    Strong = 1 << 5,      ///< Exists in the image table, the dimensions are can be trusted
    Registered = 1 << 6,  ///< True when the image is registered
    Picked = 1 << 7,      ///< Temporary flag to mark the image as picked
    Remapped = 1 << 8,    ///< Image has been remapped.
    Sparse = 1 << 9,      ///< Image has non continuous submemory.

    // Garbage Collection Flags
    BadOverlap = 1 << 10, ///< This image overlaps other but doesn't fit, has higher
                          ///< garbage collection priority
    Alias = 1 << 11,      ///< This image has aliases and has priority on garbage
                          ///< collection
    CostlyLoad = 1 << 12, ///< Protected from low-tier GC as it is costly to load back.

    // Rescaler
    Rescaled = 1 << 13,
    CheckingRescalable = 1 << 14,
    IsRescalable = 1 << 15,

    AsynchronousDecode = 1 << 16,
    IsDecoding = 1 << 17, ///< Is currently being decoded asynchronously.
};
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
    size_t registered_gpu_map = (std::numeric_limits<size_t>::max)();
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
void TextureCache::RegisterImage(ImageId image_id) {
    ImageBase& image = slot_images[image_id];
    ASSERT_MSG(False(image.flags & ImageFlagBits::Registered),
               "Trying to register an already registered image");
#ifdef __ORBIS__
    if (True(image.flags & ImageFlagBits::Registered)) {
        return;
    }
    const auto registered_gpu_map = getStorageID(current_address_space);
    if (!registered_gpu_map || *registered_gpu_map >= gpu_page_table_storage.size() / 2) {
        ASSERT_MSG(false, "PS4 registering image without a valid GPU address space");
        return;
    }
    image.registered_gpu_map = *registered_gpu_map;
#endif
    image.flags |= ImageFlagBits::Registered;
    u64 tentative_size = (std::max)(image.guest_size_bytes, image.unswizzled_size_bytes);
    if ((IsPixelFormatASTC(image.info.format) &&
         True(image.flags & ImageFlagBits::AcceleratedUpload)) ||
        True(image.flags & ImageFlagBits::Converted)) {
        tentative_size = TranscodedAstcSize(tentative_size, image.info.format);
    }
    total_used_memory += Common::AlignUp(tentative_size, 1024);
    image.lru_index = lru_cache.Insert(image_id, frame_tick);

    ForEachGPUPage(image.gpu_addr, image.guest_size_bytes, [this, image_id](u64 page) {
#ifdef __ORBIS__
        gpu_page_table_storage[slot_images[image_id].registered_gpu_map * 2][page].push_back(image_id);
#else
        (*channel_state->gpu_page_table)[page].push_back(image_id);
#endif
    });
    if (False(image.flags & ImageFlagBits::Sparse)) {
        auto map_id =
            slot_map_views.insert(image.gpu_addr, image.cpu_addr, image.guest_size_bytes, image_id);
        ForEachCPUPage(image.cpu_addr, image.guest_size_bytes,
                       [this, map_id](u64 page) { page_table[page].push_back(map_id); });
        image.map_view_id = map_id;
        return;
    }
    boost::container::small_vector<ImageViewId, 16> sparse_maps;
    ForEachSparseSegment(
        image, [this, image_id, &sparse_maps](GPUVAddr gpu_addr, DAddr cpu_addr, size_t size) {
            auto map_id = slot_map_views.insert(gpu_addr, cpu_addr, size, image_id);
            ForEachCPUPage(cpu_addr, size,
                           [this, map_id](u64 page) { page_table[page].push_back(map_id); });
            sparse_maps.push_back(map_id);
        });
    sparse_views.emplace(image_id, std::move(sparse_maps));
    ForEachGPUPage(image.gpu_addr, image.guest_size_bytes, [this, image_id](u64 page) {
#ifdef __ORBIS__
        gpu_page_table_storage[slot_images[image_id].registered_gpu_map * 2 + 1][page].push_back(image_id);
#else
        (*channel_state->sparse_page_table)[page].push_back(image_id);
#endif
    });
}

void TextureCache::UnregisterImage(ImageId image_id) {
    Image& image = slot_images[image_id];
    ASSERT_MSG(True(image.flags & ImageFlagBits::Registered),
               "Trying to unregister an already registered image");
#ifdef __ORBIS__
    // Unregistering twice frees the image's LRU slot twice; two images then share it and the LRU
    // list corrupts (MK8D crashed in LeastRecentlyUsedCache::Free). Its pages are already gone.
    if (False(image.flags & ImageFlagBits::Registered)) {
        return;
    }
    if (image.registered_gpu_map >= gpu_page_table_storage.size() / 2) {
        ASSERT_MSG(false, "PS4 unregistering image without a valid GPU address-space owner");
        return;
    }
    const auto active_gpu_map = getStorageID(current_address_space);
    if (!active_gpu_map || *active_gpu_map != image.registered_gpu_map) {
        static u32 owner_switch_logs = 0;
        if (owner_switch_logs++ < 16) {
            LOG_WARNING(Render_Vulkan,
                        "PS4 texture owner: gpu={:#x} owner={} active={} (retiring owner's pages)",
                        image.gpu_addr, image.registered_gpu_map,
                        active_gpu_map.value_or((std::numeric_limits<size_t>::max)()));
        }
    }
#endif
    image.flags &= ~ImageFlagBits::Registered;
    image.flags &= ~ImageFlagBits::BadOverlap;
    lru_cache.Free(image.lru_index);
    const auto& clear_page_table =
        [image_id](u64 page, ::Common::unordered_map<u64, std::vector<ImageId>, Common::IdentityHash<u64>>& selected_page_table) {
            const auto page_it = selected_page_table.find(page);
            if (page_it == selected_page_table.end()) {
                ASSERT_MSG(false, "Unregistering unregistered page={:#x}", page << YUZU_PAGEBITS);
                return;
            }
            std::vector<ImageId>& image_ids = page_it->second;
            const auto vector_it = std::ranges::find(image_ids, image_id);
            if (vector_it == image_ids.end()) {
                ASSERT_MSG(false, "Unregistering unregistered image in page={:#x}",
                           page << YUZU_PAGEBITS);
                return;
            }
            image_ids.erase(vector_it);
        };
    ForEachGPUPage(image.gpu_addr, image.guest_size_bytes, [this, image_id, &clear_page_table](u64 page) {
#ifdef __ORBIS__
        clear_page_table(page, gpu_page_table_storage[slot_images[image_id].registered_gpu_map * 2]);
#else
        clear_page_table(page, (*channel_state->gpu_page_table));
#endif
    });
    if (False(image.flags & ImageFlagBits::Sparse)) {
        const auto map_id = image.map_view_id;
        ForEachCPUPage(image.cpu_addr, image.guest_size_bytes, [this, map_id](u64 page) {
            const auto page_it = page_table.find(page);
            if (page_it == page_table.end()) {
                ASSERT_MSG(false, "Unregistering unregistered page={:#x}", page << YUZU_PAGEBITS);
                return;
            }
            std::vector<ImageMapId>& image_map_ids = page_it->second;
            const auto vector_it = std::ranges::find(image_map_ids, map_id);
            if (vector_it == image_map_ids.end()) {
                ASSERT_MSG(false, "Unregistering unregistered image in page={:#x}",
                           page << YUZU_PAGEBITS);
                return;
            }
            image_map_ids.erase(vector_it);
        });
        slot_map_views.erase(map_id);
        return;
    }
    ForEachGPUPage(image.gpu_addr, image.guest_size_bytes, [this, image_id, &clear_page_table](u64 page) {
#ifdef __ORBIS__
        clear_page_table(page, gpu_page_table_storage[slot_images[image_id].registered_gpu_map * 2 + 1]);
#else
        clear_page_table(page, (*channel_state->sparse_page_table));
#endif
    });
    auto it = sparse_views.find(image_id);
    ASSERT(it != sparse_views.end());
    auto& sparse_maps = it->second;
    for (auto& map_view_id : sparse_maps) {
        const auto& map_range = slot_map_views[map_view_id];
        const DAddr cpu_addr = map_range.cpu_addr;
        const std::size_t size = map_range.size;
        ForEachCPUPage(cpu_addr, size, [this, image_id](u64 page) {
            const auto page_it = page_table.find(page);
            if (page_it == page_table.end()) {
                ASSERT_MSG(false, "Unregistering unregistered page={:#x}", page << YUZU_PAGEBITS);
                return;
            }
            std::vector<ImageMapId>& image_map_ids = page_it->second;
            auto vector_it = image_map_ids.begin();
            while (vector_it != image_map_ids.end()) {
                ImageMapView& map = slot_map_views[*vector_it];
                if (map.image_id != image_id) {
                    vector_it++;
                    continue;
                }
                if (!map.picked) {
                    map.picked = true;
                }
                vector_it = image_map_ids.erase(vector_it);
            }
        });
        slot_map_views.erase(map_view_id);
    }
    sparse_views.erase(it);
}
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