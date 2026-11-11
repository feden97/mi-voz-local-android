"""Audited persistent CPU workers and numeric operation profiling, no model changes."""
import hashlib
import json
from pathlib import Path


def replace(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError('Unexpected CPU runtime anchor: ' + old[:80])
    return text.replace(old, new, 1)


def transform(text, kind):
    if kind == 'pool':
        text = '#include "mivoz_cpu_profile.h"\n' + text
        text = replace(text, '    ggml_threadpool_t   threadpool;', '    ggml_threadpool_t   threadpool;\n    bool owns_threadpool;')
        text = replace(text, '    delete[] cpu_ctx->work_data;\n    delete cpu_ctx;', '    if (cpu_ctx->owns_threadpool) ggml_threadpool_free(cpu_ctx->threadpool);\n    delete[] cpu_ctx->work_data;\n    delete cpu_ctx;')
        helper = '''static void mivoz_ensure_cpu_pool(ggml_backend_cpu_context * ctx) {
    if (ctx->threadpool) {
        mivoz_cpu_profile_pool(true, 0);
        return;
    }
    const int64_t started = ggml_time_us();
    auto params = ggml_threadpool_params_default(ctx->n_threads);
    params.poll = 0; // Sleep on the condition variable between graphs, never idle-spin.
    ctx->threadpool = ggml_threadpool_new(&params);
    ctx->owns_threadpool = ctx->threadpool != nullptr;
    mivoz_cpu_profile_pool(false, double(ggml_time_us() - started));
}

'''
        text = replace(text, 'struct ggml_backend_plan_cpu {', helper + 'struct ggml_backend_plan_cpu {')
        text = replace(text, '    struct ggml_cplan cplan = ggml_graph_plan(cgraph, cpu_ctx->n_threads, cpu_ctx->threadpool);', '    mivoz_ensure_cpu_pool(cpu_ctx);\n    struct ggml_cplan cplan = ggml_graph_plan(cgraph, cpu_ctx->n_threads, cpu_ctx->threadpool);')
        # Standalone plans must not retain a backend-owned pool that can be resized.
        text = replace(text, 'cpu_plan->cplan = ggml_graph_plan(cgraph, cpu_ctx->n_threads, cpu_ctx->threadpool);', 'cpu_plan->cplan = ggml_graph_plan(cgraph, cpu_ctx->n_threads, cpu_ctx->owns_threadpool ? nullptr : cpu_ctx->threadpool);')
        text = replace(text, '    ctx->threadpool          = NULL;', '    ctx->threadpool          = NULL;\n    ctx->owns_threadpool     = false;')
        text = replace(text, '    ctx->n_threads = n_threads;', '''    if (ctx->owns_threadpool && ctx->n_threads != n_threads) {
        ggml_threadpool_free(ctx->threadpool);
        ctx->threadpool = nullptr;
        ctx->owns_threadpool = false;
    }
    ctx->n_threads = n_threads;''')
        text = replace(text, '    ctx->threadpool = threadpool;', '''    if (ctx->owns_threadpool && ctx->threadpool != threadpool) {
        ggml_threadpool_free(ctx->threadpool);
        ctx->owns_threadpool = false;
    }
    ctx->threadpool = threadpool;''')
        return text
    text = '#include "mivoz_cpu_profile.h"\n' + text
    anchor = 'static thread_ret_t ggml_graph_compute_thread(void * data) {'
    definitions = '''static _Thread_local struct mivoz_cpu_stats mivoz_stats;
static _Thread_local bool mivoz_profiling;
static _Thread_local int mivoz_phase;
void mivoz_cpu_profile_begin(void) {
    memset(&mivoz_stats, 0, sizeof(mivoz_stats));
    mivoz_phase = 0;
    mivoz_profiling = true;
}
void mivoz_cpu_profile_phase(int phase) { mivoz_phase = phase == 1 ? 1 : 0; }
struct mivoz_cpu_stats mivoz_cpu_profile_read(void) { return mivoz_stats; }
void mivoz_cpu_profile_pool(bool reused, double elapsed_us) {
    if (!mivoz_profiling) return;
    if (reused) mivoz_stats.pools_reused++;
    else { mivoz_stats.pools_created++; mivoz_stats.pool_create_us += elapsed_us; }
}
static void mivoz_record_op(int op, int64_t started) {
    if (mivoz_profiling && op >= 0 && op < GGML_OP_COUNT) {
        mivoz_stats.us[mivoz_phase][op] += ggml_time_us() - started;
        mivoz_stats.calls[mivoz_phase][op]++;
    }
}

'''
    text = replace(text, anchor, definitions + anchor)
    text = replace(text, '    for (int node_n = 0; node_n < cgraph->n_nodes &&', '    int mivoz_previous_op = -1;\n    int64_t mivoz_started = 0;\n    for (int node_n = 0; node_n < cgraph->n_nodes &&')
    text = replace(text, '        // TODO: move fused-op detection into ggml_graph_plan', '''        if (state->ith == 0 && mivoz_profiling) {
            mivoz_record_op(mivoz_previous_op, mivoz_started);
            mivoz_previous_op = node->op;
            mivoz_started = ggml_time_us();
        }
        // TODO: move fused-op detection into ggml_graph_plan''')
    text = replace(text, '    ggml_barrier(state->threadpool);\n\n#ifdef GGML_USE_CPU_RISCV64_SPACEMIT', '    ggml_barrier(state->threadpool);\n    if (state->ith == 0) mivoz_record_op(mivoz_previous_op, mivoz_started);\n\n#ifdef GGML_USE_CPU_RISCV64_SPACEMIT')
    return text


def apply(root, check_only=False):
    directory = root / 'external/qwen3-tts.cpp/ggml/src/ggml-cpu'
    manifest = json.loads((root / 'patches/cpu-runtime-manifest.json').read_text())
    for entry in manifest:
        path = directory / entry['file']
        text = path.read_text(encoding='utf-8')
        digest = lambda value: hashlib.sha256(value.encode()).hexdigest()
        if digest(text) == entry['patched']: continue
        if digest(text) != entry['upstream']: raise RuntimeError('Unexpected CPU source: ' + entry['file'])
        changed = transform(text, entry['kind'])
        if digest(changed) != entry['patched'] or check_only: raise RuntimeError('CPU patch not ready: ' + entry['file'])
        path.write_text(changed, encoding='utf-8', newline='\n')
    header = (root / 'patches/mivoz_cpu_profile.h').read_text()
    for target in (directory / 'mivoz_cpu_profile.h', root / 'external/qwen3-tts.cpp/src/mivoz_cpu_profile.h'):
        if not target.is_file() or target.read_text() != header:
            if check_only: raise RuntimeError('CPU profile header is stale')
            target.write_text(header, encoding='utf-8', newline='\n')
