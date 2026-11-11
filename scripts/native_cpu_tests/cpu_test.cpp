#include "ggml.h"
#include "ggml-cpu.h"
#include "mivoz_cpu_profile.h"
#include "mivoz_decoder_snake.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <chrono>
#ifdef _WIN32
#include <windows.h>
static double cpu_ms() {
    FILETIME a,b,k,u;
    GetProcessTimes(GetCurrentProcess(), &a,&b,&k,&u);
    ULARGE_INTEGER x,y; x.LowPart=k.dwLowDateTime;x.HighPart=k.dwHighDateTime;
    y.LowPart=u.dwLowDateTime;y.HighPart=u.dwHighDateTime;
    return (x.QuadPart+y.QuadPart)/10000.0;
}
#else
#include <ctime>
static double cpu_ms() { return 1000.0*std::clock()/CLOCKS_PER_SEC; }
#endif
static void check(bool ok, const char * what) { if (!ok) { std::fprintf(stderr,"FAIL: %s\n",what); std::exit(1); } }
static bool abort_now(void * value) { return *static_cast<bool *>(value); }
int main() {
    ggml_backend_t backend=ggml_backend_cpu_init();
    int cases=0;
    for (int threads: {1,2,4}) for (int length: {1,3,127,1024}) {
        ggml_backend_cpu_set_n_threads(backend,threads);
        ggml_init_params params={16*1024*1024,nullptr,false};
        auto * ctx=ggml_init(params);
        auto * x=ggml_new_tensor_3d(ctx,GGML_TYPE_F32,length,7,2);
        auto * a=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,1,7);
        auto * b=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,1,7);
        for (int i=0;i<7;++i) { ((float *)a->data)[i]=(i+1)*0.37f;((float *)b->data)[i]=(i+1)*0.12f; }
        auto * mul=ggml_mul(ctx,x,a);
        auto * sine=ggml_sin(ctx,mul);
        auto * square=ggml_sqr(ctx,sine);
        auto * ref=ggml_add(ctx,x,ggml_mul(ctx,square,b));
        check(mivoz_can_fuse_snake(x,a,b),"eligible shape");
        auto * fused=ggml_map_custom3(ctx,x,a,b,mivoz_snake_f32,GGML_N_TASKS_MAX,nullptr);
        auto * graph=ggml_new_graph(ctx);
        ggml_build_forward_expand(graph,ref);ggml_build_forward_expand(graph,fused);
        mivoz_cpu_profile_begin();
        for (int iteration=0;iteration<10;++iteration) {
            for (int64_t i=0;i<ggml_nelements(x);++i) ((float *)x->data)[i]=float((i*13+iteration*7)%2049-1024)*0.013f;
            mivoz_cpu_profile_phase(iteration%2);
            check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_SUCCESS,"graph execution");
            check(std::memcmp(ref->data,fused->data,ggml_nbytes(ref))==0,"bit-exact fused Snake");++cases;
        }
        auto stats=mivoz_cpu_profile_read();
        check(stats.pools_reused>=9,"workers reused");
        check(stats.pools_created<=1,"at most one pool allocation");
        check(stats.calls[1][GGML_OP_MAP_CUSTOM3]>0,"decoder operation profile");
        check(!mivoz_can_fuse_snake(ggml_transpose(ctx,x),a,b),"fallback on other layouts");
        bool aborted=true;ggml_backend_cpu_set_abort_callback(backend,abort_now,&aborted);
        check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_ABORTED,"cooperative abort");
        aborted=false;check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_SUCCESS,"reuse after abort");
        ggml_backend_cpu_set_abort_callback(backend,nullptr,nullptr);
        auto plan=ggml_backend_graph_plan_create(backend,graph);
        ggml_backend_cpu_set_n_threads(backend,threads==4?2:4);
        check(ggml_backend_graph_plan_compute(backend,plan)==GGML_STATUS_SUCCESS,"standalone plan after resizing pool");
        ggml_backend_graph_plan_free(backend,plan);
        ggml_free(ctx);
    }
    const double started=cpu_ms();std::this_thread::sleep_for(std::chrono::milliseconds(400));
    const double idle=cpu_ms()-started;check(idle<100,"workers sleep at idle");
    ggml_backend_free(backend);
    auto params=ggml_threadpool_params_default(2);params.poll=0;
    auto external=ggml_threadpool_new(&params);backend=ggml_backend_cpu_init();
    ggml_backend_cpu_set_threadpool(backend,external);ggml_backend_free(backend);
    ggml_threadpool_free(external); // Backend must not destroy a caller-owned pool.
    auto * ctx=ggml_init({32*1024*1024,nullptr,false});
    auto * x=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,16384,8);
    auto * a=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,1,8);
    auto * b=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,1,8);
    for (int i=0;i<8;++i) { ((float *)a->data)[i]=0.37f*(i+1);((float *)b->data)[i]=0.12f*(i+1); }
    for (int64_t i=0;i<ggml_nelements(x);++i) ((float *)x->data)[i]=float(i%2049-1024)*0.013f;
    auto * ref=ggml_add(ctx,x,ggml_mul(ctx,ggml_sqr(ctx,ggml_sin(ctx,ggml_mul(ctx,x,a))),b));
    auto * fused=ggml_map_custom3(ctx,x,a,b,mivoz_snake_f32,GGML_N_TASKS_MAX,nullptr);
    auto * gr=ggml_new_graph(ctx);ggml_build_forward_expand(gr,ref);
    auto * gf=ggml_new_graph(ctx);ggml_build_forward_expand(gf,fused);
    backend=ggml_backend_cpu_init();ggml_backend_cpu_set_n_threads(backend,4);
    auto measure=[&](ggml_cgraph * graph, bool disposable) {
        auto plan=ggml_graph_plan(graph,4,nullptr);
        auto * work=static_cast<uint8_t *>(std::malloc(plan.work_size));plan.work_data=work;
        const auto start=std::chrono::steady_clock::now();
        for (int i=0;i<30;++i) check((disposable?ggml_graph_compute(graph,&plan):ggml_backend_graph_compute(backend,graph))==GGML_STATUS_SUCCESS,"benchmark execution");
        const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        std::free(work);return ms;
    };
    const double disposable=measure(gr,true), pooled=measure(gr,false), fused_ms=measure(gf,false);
    check(std::memcmp(ref->data,fused->data,ggml_nbytes(ref))==0,"large Snake equivalence");
    std::printf("Host-only 30 graphs: reference/disposable %.2f ms; reference/reused %.2f ms; fused/reused %.2f ms\n",disposable,pooled,fused_ms);
    ggml_backend_free(backend);ggml_free(ctx);
    std::printf("PASS: %d bit-exact Snake comparisons, threads 1/2/4, pool reuse/abort/ownership; idle CPU %.2f ms / 400 ms\n",cases,idle);
}
