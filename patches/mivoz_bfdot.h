#pragma once

#include "mivoz_bf16_dot.h"
#include <asm/hwcap.h>
#include <sys/auxv.h>

static inline bool mivoz_bench_has_bfdot() {
    static const bool supported = (getauxval(AT_HWCAP2) & HWCAP2_BF16) != 0;
    return supported;
}

// Keep the extension in a single function. The rest of GGML retains its
// baseline ISA, and only the runtime-checked caller may enter this function.
__attribute__((target("bf16"), noinline))
static bool mivoz_bench_bfdot(int n, float * result, const uint16_t * x, const uint16_t * y) {
    const uint16x8_t magnitude = vdupq_n_u16(0x7fff);
    const uint16x8_t min_magnitude = vdupq_n_u16(79u << 7); // 2^-48
    const uint16x8_t max_magnitude = vdupq_n_u16(183u << 7); // 2^56
    const uint16x8_t zero = vdupq_n_u16(0);
    double total = 0.0;
    int i = 0;
    while (i < n) {
        const int end = i + (n - i < 256 ? n - i : 256);
        float32x4_t sum = vdupq_n_f32(0);
        for (; i < end; i += 8) {
            const uint16x8_t a = vld1q_u16(x + i);
            const uint16x8_t b = vld1q_u16(y + i);
            const uint16x8_t ax = vandq_u16(magnitude, a);
            const uint16x8_t bx = vandq_u16(magnitude, b);
            const uint16x8_t large = vcgeq_u16(vmaxq_u16(ax, bx), max_magnitude);
            // Use the baseline for tiny nonzero operands and special values.
            // BFDOT can flush denormals and use a different rounding rule.
            const uint16x8_t small_a = vandq_u16(vcltq_u16(ax, min_magnitude),
                                                vcgtq_u16(ax, zero));
            const uint16x8_t small_b = vandq_u16(vcltq_u16(bx, min_magnitude),
                                                vcgtq_u16(bx, zero));
            if (vmaxvq_u16(vorrq_u16(large, vorrq_u16(small_a, small_b))) != 0) return false;
            sum = vbfdotq_f32(sum, vreinterpretq_bf16_u16(a), vreinterpretq_bf16_u16(b));
        }
        total += vaddvq_f64(vcvt_f64_f32(vget_low_f32(sum)));
        total += vaddvq_f64(vcvt_f64_f32(vget_high_f32(sum)));
    }
    *result = float(total);
    return true;
}

static inline bool mivoz_bench_vec_dot(int n, float * result, const void * x, const void * y) {
    if (mivoz_bench_has_bfdot() && n > 0 && n % 8 == 0 &&
        mivoz_bench_bfdot(n, result, static_cast<const uint16_t *>(x), static_cast<const uint16_t *>(y))) {
        return true;
    }
    return mivoz_vec_dot_bf16_neon(n, result, x, y);
}

// Redirect GGML vec.cpp only in the standalone benchmark build.
#ifdef MIVOZ_BFDOT_REDIRECT
#define mivoz_vec_dot_bf16_neon mivoz_bench_vec_dot
#endif
