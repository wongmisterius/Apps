#include <cassert>
#include <cstdio>
#include <utility>
#include <vector>

#include "video_core/delayed_destruction_ring.h"

struct Resource {
    std::vector<int>* events;
    int id;
    bool* finished;

    Resource(std::vector<int>& log, int value, bool& gpu_finished)
        : events(&log), id(value), finished(&gpu_finished) {}
    Resource(const Resource&) = delete;
    Resource(Resource&& other) noexcept
        : events(std::exchange(other.events, nullptr)), id(other.id), finished(other.finished) {}
    ~Resource() {
        if (events) {
            assert(*finished && "retired before completion");
            events->push_back(id);
        }
    }
};

int main() {
    using Ring = VideoCommon::DelayedDestructionRing<Resource, 8>;
    std::vector<int> events;
    bool finished = false;
    {
        Ring empty;
        for (int i = 0; i < 32; ++i) {
            assert(!empty.HasPendingDestruction());
            assert(empty.PendingDestructionCount() == 0);
            empty.Tick();
        }
    }
    {
        Ring ring;
        ring.Push(Resource(events, 1, finished));
        for (int i = 0; i < 7; ++i) {
            assert(!ring.HasPendingDestruction());
            ring.Tick();
            assert(events.empty());
        }
        assert(ring.HasPendingDestruction());
        assert(ring.PendingDestructionCount() == 1);
        finished = true;
        ring.Tick();
        assert((events == std::vector<int>{1}));
        for (int i = 0; i < 16; ++i) ring.Tick();
        assert(events.size() == 1);
    }
    events.clear();
    {
        Ring ring;
        ring.Push(Resource(events, 2, finished));
        ring.Tick();
        ring.Push(Resource(events, 3, finished));
        for (int i = 0; i < 6; ++i) ring.Tick();
        assert(ring.PendingDestructionCount() == 1);
        ring.Tick();
        assert((events == std::vector<int>{2}));
        assert(ring.PendingDestructionCount() == 1);
        ring.Tick();
        assert((events == std::vector<int>{2, 3}));
    }
    events.clear();
    {
        Ring ring;
        for (int i = 0; i < 100; ++i) ring.Push(Resource(events, i, finished));
        for (int i = 0; i < 7; ++i) ring.Tick();
        assert(events.empty());
        assert(ring.PendingDestructionCount() == 100);
        ring.Tick();
        assert(events.size() == 100); // moving vector elements never retires their handles
    }
    events.clear();
    finished = false;
    {
        Ring framebuffers, views, images;
        framebuffers.Push(Resource(events, 10, finished));
        views.Push(Resource(events, 20, finished));
        images.Push(Resource(events, 30, finished));
        int synchronizations = 0;
        for (int i = 0; i < 8; ++i) {
            if (framebuffers.HasPendingDestruction() || views.HasPendingDestruction() ||
                images.HasPendingDestruction()) {
                ++synchronizations;
                events.push_back(0); // the completion event occurs before any destructor
                finished = true;
            }
            framebuffers.Tick();
            views.Tick();
            images.Tick();
        }
        assert(synchronizations == 1);
        assert((events == std::vector<int>{0, 10, 20, 30}));
    }
    std::puts("PASS: empty buckets, 8-tick lifetime, separate buckets, move ownership, completion gate, retirement order");
}
