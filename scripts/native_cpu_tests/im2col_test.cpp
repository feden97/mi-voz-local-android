#include "ggml.h"
#include "ggml-cpu.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <initializer_list>

static void check(bool value, const char * label) {
    if (!value) { std::fprintf(stderr,"FAIL: %s\n",label); std::exit(1); }
}
struct Shape { int width, channels, batches, kernel, stride, padding, dilation, gap; };
static void run(ggml_backend_t backend, Shape shape, ggml_type input_type, ggml_type output_type, bool benchmark) {
    auto * ctx=ggml_init({64*1024*1024,nullptr,false});
    const size_t element=ggml_type_size(input_type), pitch=(shape.width+shape.gap)*element;
    auto * backing=ggml_new_tensor_1d(ctx,input_type,(shape.width+shape.gap)*shape.channels*shape.batches);
    for (int64_t i=0;i<ggml_nelements(backing);++i) {
        const float edge[]={0.0f,-0.0f,0.33333f,65504.0f,100000.0f,-100000.0f,1e-40f,
            std::numeric_limits<float>::infinity(),-std::numeric_limits<float>::infinity(),
            std::numeric_limits<float>::quiet_NaN(),0x1p-24f};
        const float value=benchmark?float(i%1023-511)*0.013f:edge[i%11];
        if(input_type==GGML_TYPE_F32) static_cast<float *>(backing->data)[i]=value;
        else static_cast<ggml_fp16_t *>(backing->data)[i]=ggml_fp32_to_fp16(value);
    }
    auto * one=ggml_view_3d(ctx,backing,shape.width,shape.channels,shape.batches,pitch,pitch*shape.channels,0);
    auto * two=ggml_view_4d(ctx,backing,shape.width,1,shape.channels,shape.batches,
        shape.width*element,pitch,pitch*shape.channels,0);
    auto * k1=ggml_new_tensor_3d(ctx,GGML_TYPE_F16,shape.kernel,shape.channels,1);
    auto * k2=ggml_reshape_4d(ctx,k1,shape.kernel,1,shape.channels,1);
    auto * optimized=ggml_im2col(ctx,k1,one,shape.stride,1,shape.padding,0,shape.dilation,1,false,output_type);
    // Height and kernel height are one: same windows, using untouched 2D dispatch.
    auto * original=ggml_im2col(ctx,k2,two,shape.stride,1,shape.padding,0,shape.dilation,1,true,output_type);
    auto * a=ggml_new_graph(ctx);ggml_build_forward_expand(a,original);
    auto * b=ggml_new_graph(ctx);ggml_build_forward_expand(b,optimized);
    auto measure=[&](ggml_cgraph * graph, int repetitions) {
        if(benchmark) check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_SUCCESS,"warmup");
        auto started=std::chrono::steady_clock::now();
        for(int i=0;i<repetitions;++i) check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_SUCCESS,"compute");
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
    };
    double old_ms=0,new_ms=0;
    for(int round=0;round<(benchmark?4:1);++round) {
        if(round%2) { new_ms+=measure(b,20);old_ms+=measure(a,20); }
        else { old_ms+=measure(a,benchmark?20:1);new_ms+=measure(b,benchmark?20:1); }
    }
    check(ggml_nbytes(original)==ggml_nbytes(optimized),"output shape size");
    check(std::memcmp(original->data,optimized->data,ggml_nbytes(original))==0,"bit-exact convolution windows");
    if(!benchmark) {
        auto * vertical_padding=ggml_im2col(ctx,k1,one,shape.stride,1,shape.padding,1,shape.dilation,1,false,output_type);
        auto * fallback=ggml_new_graph(ctx);ggml_build_forward_expand(fallback,vertical_padding);
        check(ggml_backend_graph_compute(backend,fallback)==GGML_STATUS_SUCCESS,"vertical padding fallback");
        for(size_t i=0;i<ggml_nbytes(vertical_padding);++i) check(static_cast<unsigned char *>(vertical_padding->data)[i]==0,"original 1D vertical padding behavior");
    }
    if(benchmark) std::printf("Host-only mean of 4 alternating runs, 20 im2col graphs (%d channels, K%d): original-equivalent %.2f ms; channel blocks %.2f ms\n",shape.channels,shape.kernel,old_ms/4,new_ms/4);
    ggml_free(ctx);
}
int main() {
    auto backend=ggml_backend_cpu_init();
    const Shape shapes[]={ {8,1,1,1,1,0,1,0}, {65,3,2,3,1,1,1,0}, {257,17,1,7,1,0,1,3},
        {32,3,2,3,2,2,2,0}, {19,7,3,1,3,0,1,2}, {129,8,2,7,2,5,2,3},
        {1,9,1,3,1,2,1,0}, {41,33,1,3,1,0,1,0} };
    int cases=0;
    for(int threads: {1,2,4}) {
        ggml_backend_cpu_set_n_threads(backend,threads);
        for(auto shape:shapes) {
            run(backend,shape,GGML_TYPE_F32,GGML_TYPE_F32,false);++cases;
            run(backend,shape,GGML_TYPE_F32,GGML_TYPE_F16,false);++cases;
            run(backend,shape,GGML_TYPE_F16,GGML_TYPE_F16,false);++cases;
        }
    }
    run(backend,{4096,128,1,3,1,1,1,0},GGML_TYPE_F32,GGML_TYPE_F16,true);
    run(backend,{256,1024,1,7,1,3,1,0},GGML_TYPE_F32,GGML_TYPE_F16,true);
    ggml_backend_free(backend);
    std::printf("PASS: %d im2col cases, FP32/FP16, strides/dilation/padding, batches, views, NaN/Inf/subnormals, 1/2/4 threads\n",cases);
}
