#pragma once
#include "ggml.h"
#include <cstring>

// Typed contiguous channel blocks: preserve input locality and avoid interleaved writes.
template<class Input, class Output, class Convert>
static void mivoz_im2col_block(ggml_tensor * dst, int ith, int nth, Convert convert) {
    const auto * input = dst->src[1];
    const auto * op = reinterpret_cast<const int32_t *>(dst->op_params);
    const int64_t iw=input->ne[0], channels=input->ne[1], batches=input->ne[2];
    const int64_t kw=dst->src[0]->ne[0], ow=dst->ne[1];
    const int64_t first=channels*ith/nth, last=channels*(ith+1)/nth;
    auto * output=static_cast<Output *>(dst->data);
    const auto * data=static_cast<const char *>(input->data);
    const size_t channel_stride=input->nb[1], batch_stride=input->nb[2];
    for(int64_t batch=0;batch<batches;++batch) for(int64_t position=0;position<ow;++position) {
        const int64_t start=position*op[0]-op[2];
        const bool interior=start>=0 && start+(kw-1)*op[4]<iw;
        for(int64_t channel=first;channel<last;++channel) {
            const auto * src=reinterpret_cast<const Input *>(data+batch*batch_stride+channel*channel_stride);
            auto * target=output+((batch*ow+position)*channels+channel)*kw;
            for(int64_t k=0;k<kw;++k) {
                const int64_t at=start+k*op[4];
                target[k]=(interior || (at>=0 && at<iw))?convert(src[at]):Output(0);
            }
        }
    }
}

// Conversion is supplied by GGML itself: no change of dtype or rounding.
template<class Convert>
static bool mivoz_im2col_1d(ggml_tensor * dst, int ith, int nth, Convert to_half) {
    const auto * kernel = dst->src[0];
    const auto * input = dst->src[1];
    const auto * op = reinterpret_cast<const int32_t *>(dst->op_params);
    if (op[6] != 0 || op[3] != 0 || op[0] <= 0 || op[4] <= 0 || input->ne[3] != 1 || !ggml_is_contiguous(dst) ||
        input->nb[0] != ggml_type_size(input->type) ||
        (input->type != GGML_TYPE_F32 && input->type != GGML_TYPE_F16) ||
        (dst->type != GGML_TYPE_F16 && dst->type != GGML_TYPE_F32) ||
        (dst->type == GGML_TYPE_F32 && input->type != GGML_TYPE_F32) ||
        (dst->type == GGML_TYPE_F16 && kernel->type != GGML_TYPE_F16)) return false;
    const int64_t iw = input->ne[0], channels = input->ne[1], batches = input->ne[2];
    const int64_t kw = kernel->ne[0], ow = dst->ne[1];
    if (iw <= 0 || channels <= 0 || batches <= 0 || kw <= 0 || ow <= 0 || nth <= 0) return false;
    if (channels < nth * 4) return false;
    if (dst->type == GGML_TYPE_F32) mivoz_im2col_block<float,float>(dst,ith,nth,[](float value){return value;});
    else if (input->type == GGML_TYPE_F16) mivoz_im2col_block<ggml_fp16_t,ggml_fp16_t>(dst,ith,nth,[](ggml_fp16_t value){return value;});
    else mivoz_im2col_block<float,ggml_fp16_t>(dst,ith,nth,to_half);
    return true;
}
