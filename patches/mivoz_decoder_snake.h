#pragma once
#include "ggml.h"
#include <cmath>

// Same FP32 operation order as MUL -> SIN -> SQR -> MUL -> ADD, in one pass.
// Only the existing contiguous CPU/F32 case is eligible; other layouts fall back.
static void mivoz_snake_f32(ggml_tensor * dst, const ggml_tensor * x,
                            const ggml_tensor * alpha, const ggml_tensor * beta,
                            int ith, int nth, void *) {
#if defined(__clang__)
#pragma clang fp contract(off)
#endif
    const auto * a = static_cast<const float *>(alpha->data);
    const auto * b = static_cast<const float *>(beta->data);
    const auto * input = static_cast<const float *>(x->data);
    auto * output = static_cast<float *>(dst->data);
    for (int64_t row = ith; row < ggml_nrows(x); row += nth) {
        const int64_t channel = row % x->ne[1];
        for (int64_t i = 0; i < x->ne[0]; ++i) {
            const int64_t at = row * x->ne[0] + i;
            const float ax = input[at] * a[channel];
            const float s = std::sin(ax);
            const float square = s * s;
            const float scaled = square * b[channel];
            output[at] = input[at] + scaled;
        }
    }
}

static bool mivoz_can_fuse_snake(const ggml_tensor * x, const ggml_tensor * a, const ggml_tensor * b) {
    return x && a && b && x->type == GGML_TYPE_F32 && a->type == GGML_TYPE_F32 && b->type == GGML_TYPE_F32 &&
        ggml_is_contiguous(x) && ggml_is_contiguous(a) && ggml_is_contiguous(b) &&
        a->ne[0] == 1 && b->ne[0] == 1 && a->ne[1] == x->ne[1] && b->ne[1] == x->ne[1] &&
        a->ne[2] == 1 && a->ne[3] == 1 && b->ne[2] == 1 && b->ne[3] == 1;
}
