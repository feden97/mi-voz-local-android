#pragma once

#include <cstdint>
#include <cstring>

#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>

// BF16 is widened by shifting its bits, retaining its FP32 exponent range.
// No conversion through FP16 and no BF16 hardware instruction is required.
// Each group is accumulated in FP32; groups are combined in FP64 like GGML's
// reference. Extremely large/non-finite inputs use the unchanged reference path.
static inline bool mivoz_vec_dot_bf16_neon(int n, float * result,
                                          const void * vx, const void * vy) {
    const auto * x = static_cast<const uint16_t *>(vx);
    const auto * y = static_cast<const uint16_t *>(vy);
    const uint16x8_t magnitude_mask = vdupq_n_u16(0x7fff);
    const uint16x8_t safe_limit = vdupq_n_u16(183u << 7); // |input| < 2^56
    double total = 0.0;
    int i = 0;
    while (i + 8 <= n) {
        // Bound the accumulation length as well as the input magnitude. This
        // prevents a new intermediate overflow in the FP32 SIMD accumulators.
        const int end = i + ((n - i < 256 ? n - i : 256) & ~7);
        float32x4_t lo = vdupq_n_f32(0.0f);
        float32x4_t hi = vdupq_n_f32(0.0f);
        for (; i < end; i += 8) {
            const uint16x8_t bx = vld1q_u16(x + i);
            const uint16x8_t by = vld1q_u16(y + i);
            const uint16x8_t largest = vmaxq_u16(vandq_u16(bx, magnitude_mask), vandq_u16(by, magnitude_mask));
            if (vmaxvq_u16(vcgeq_u16(largest, safe_limit)) != 0) return false;
            const float32x4_t xl = vreinterpretq_f32_u32(vshll_n_u16(vget_low_u16(bx), 16));
            const float32x4_t xh = vreinterpretq_f32_u32(vshll_n_u16(vget_high_u16(bx), 16));
            const float32x4_t yl = vreinterpretq_f32_u32(vshll_n_u16(vget_low_u16(by), 16));
            const float32x4_t yh = vreinterpretq_f32_u32(vshll_n_u16(vget_high_u16(by), 16));
            lo = vaddq_f32(lo, vmulq_f32(xl, yl));
            hi = vaddq_f32(hi, vmulq_f32(xh, yh));
        }
        // Widen before horizontal addition to avoid cancellation/overflow in
        // that reduction. Summation order can differ from scalar GGML.
        total += vaddvq_f64(vcvt_f64_f32(vget_low_f32(lo)));
        total += vaddvq_f64(vcvt_f64_f32(vget_high_f32(lo)));
        total += vaddvq_f64(vcvt_f64_f32(vget_low_f32(hi)));
        total += vaddvq_f64(vcvt_f64_f32(vget_high_f32(hi)));
    }
    for (; i < n; ++i) {
        if ((x[i] & 0x7fff) >= (183u << 7) || (y[i] & 0x7fff) >= (183u << 7)) return false;
        uint32_t bx = uint32_t(x[i]) << 16;
        uint32_t by = uint32_t(y[i]) << 16;
        float fx, fy;
        std::memcpy(&fx, &bx, sizeof(fx));
        std::memcpy(&fy, &by, sizeof(fy));
        total += double(fx * fy);
    }
    *result = float(total);
    return true;
}
#endif
