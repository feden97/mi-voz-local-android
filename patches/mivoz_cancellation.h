#pragma once

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "mivoz_cpu_profile.h"
#include <atomic>

// The Android app serializes its single native pipeline. This flag deliberately
// has process lifetime: UI cancellation never dereferences a potentially freed
// context and registered CPU callbacks never point at expired operation state.
namespace mivoz_cancellation {
inline std::atomic<bool> requested{false};

inline bool should_abort(void *) {
    return requested.load(std::memory_order_acquire);
}

inline enum ggml_status compute(ggml_backend_sched_t scheduler, ggml_cgraph * graph) {
    if (should_abort(nullptr)) return GGML_STATUS_ABORTED;
    for (int i = 0; i < ggml_backend_sched_get_n_backends(scheduler); ++i) {
        auto backend = ggml_backend_sched_get_backend(scheduler, i);
        if (ggml_backend_is_cpu(backend)) {
            ggml_backend_cpu_set_abort_callback(backend, should_abort, nullptr);
        }
    }
    const auto status = ggml_backend_sched_graph_compute(scheduler, graph);
    return should_abort(nullptr) ? GGML_STATUS_ABORTED : status;
}

inline enum ggml_status compute_decode(ggml_backend_sched_t scheduler, ggml_cgraph * graph) {
    struct phase_scope {
        phase_scope() { mivoz_cpu_profile_phase(1); }
        ~phase_scope() { mivoz_cpu_profile_phase(0); }
    } phase;
    return compute(scheduler, graph);
}
} // namespace mivoz_cancellation
