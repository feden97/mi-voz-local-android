#pragma once

#include "ggml.h"

#include <algorithm>
#include <cstdint>
#include <limits>

inline void mivoz_sliding_causal_mask(
    ggml_tensor * dst, const ggml_tensor * src, int thread, int threads, void * userdata) {
    const int64_t window = *static_cast<const uint32_t *>(userdata);
    const int64_t keys = src->ne[0];
    GGML_ASSERT(keys == src->ne[1] && window > 0);
    GGML_ASSERT(ggml_is_contiguous(src) && ggml_is_contiguous(dst));
    const float * input = ggml_get_data_f32(src);
    float * output = ggml_get_data_f32(dst);
    const float masked = -std::numeric_limits<float>::infinity();

    for (int64_t row = thread; row < ggml_nrows(src); row += threads) {
        const int64_t end = row % keys + 1;
        const int64_t start = std::max<int64_t>(0, end - window);
        const float * source = input + row * keys;
        float * target = output + row * keys;
        std::fill(target, target + start, masked);
        std::copy(source + start, source + end, target + start);
        std::fill(target + end, target + keys, masked);
    }
}

inline ggml_tensor * mivoz_decoder_attention_mask(
    ggml_context * ctx, ggml_tensor * scores, uint32_t & window) {
    if (window == 0 || scores->ne[0] <= window) {
        return ggml_diag_mask_inf(ctx, scores, 0);
    }
    // The decoder owns the window for as long as its cached graph exists.
    return ggml_map_custom1(ctx, scores, mivoz_sliding_causal_mask, GGML_N_TASKS_MAX, &window);
}
