#pragma once
#include "ggml.h"
#include "ggml-backend.h"
#ifdef __cplusplus
extern "C" {
#endif
struct mivoz_cpu_stats {
    double us[2][GGML_OP_COUNT];
    uint64_t calls[2][GGML_OP_COUNT];
    uint64_t pools_created, pools_reused;
    double pool_create_us;
};
GGML_BACKEND_API void mivoz_cpu_profile_begin(void);
GGML_BACKEND_API void mivoz_cpu_profile_phase(int phase);
GGML_BACKEND_API struct mivoz_cpu_stats mivoz_cpu_profile_read(void);
GGML_BACKEND_API void mivoz_cpu_profile_pool(bool reused, double elapsed_us);
#ifdef __cplusplus
}
#endif
