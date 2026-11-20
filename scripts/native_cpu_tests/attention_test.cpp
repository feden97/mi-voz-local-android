#include "ggml-cpu.h"
#include "mivoz_decoder_attention.h"

#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <limits>

int main() {
    ggml_backend_t backend = ggml_backend_cpu_init();
    for (int threads : {1, 4}) {
        ggml_backend_cpu_set_n_threads(backend, threads);
        for (int frames : {1, 64, 72, 73, 208}) {
            for (uint32_t window : {0u, 1u, 72u, UINT32_MAX}) {
                auto * ctx = ggml_init({4 * 1024 * 1024, nullptr, false});
                auto * scores = ggml_new_tensor_4d(ctx, GGML_TYPE_F32, frames, frames, 2, 2);
                float * input = ggml_get_data_f32(scores);
                for (int64_t i = 0; i < ggml_nelements(scores); ++i) {
                    input[i] = static_cast<float>(i + 1);
                }
                auto * output = mivoz_decoder_attention_mask(ctx, scores, window);
                auto * graph = ggml_new_graph(ctx);
                ggml_build_forward_expand(graph, output);
                if (ggml_backend_graph_compute(backend, graph) != GGML_STATUS_SUCCESS) {
                    return EXIT_FAILURE;
                }
                const float * actual = ggml_get_data_f32(output);
                for (int64_t row = 0; row < ggml_nrows(scores); ++row) {
                    const int64_t query = row % frames;
                    for (int key = 0; key < frames; ++key) {
                        const bool visible = key <= query && (window == 0 || query - key < window);
                        const int64_t i = row * frames + key;
                        const float expected = visible ? input[i] : -std::numeric_limits<float>::infinity();
                        if (actual[i] != expected) {
                            std::fprintf(stderr, "Unexpected mask at %d frames, window %u, row %lld, key %d\n",
                                         frames, window, static_cast<long long>(row), key);
                            return EXIT_FAILURE;
                        }
                    }
                }
                ggml_free(ctx);
            }
        }
    }
    ggml_backend_free(backend);
    std::puts("PASS: decoder attention window, legacy fallback, query boundaries and batch/head rows");
}
