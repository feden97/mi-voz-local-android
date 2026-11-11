#pragma once

#include <chrono>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <limits>
#include <locale>
#include <random>
#include <sstream>
#include <string>

// The app serializes synthesis. Thread-local storage additionally keeps metrics
// isolated from unrelated encoder calls. Only fixed names and numbers are stored.
namespace mivoz_native_metrics {
inline thread_local int64_t effective_seed = -1;
inline thread_local std::string transformer_json = "null";

inline int64_t begin(int64_t requested_seed) {
    if (requested_seed < 0) {
        std::random_device random;
        requested_seed = int64_t(((uint64_t(random()) << 32) ^ uint64_t(random())) & uint64_t(INT64_MAX));
    }
    effective_seed = requested_seed;
    transformer_json = "null";
    return requested_seed;
}

inline double safe_ms(double value) { return std::isfinite(value) && value >= 0.0 ? value : 0.0; }

template<class Timing> struct timing_scope {
    Timing *& active;
    Timing & value;
    std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();
    timing_scope(Timing *& destination, Timing & timing) : active(destination), value(timing) { active = &value; }
    ~timing_scope() {
        active = nullptr; // Also clear the stack pointer on cancellation/failure.
        value.t_generate_total_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        std::ostringstream out;
        out.imbue(std::locale::classic());
        out << std::fixed << std::setprecision(3) << '{';
#define MIVOZ_TIMING(name) out << "\"" #name "\":" << safe_ms(value.name) << ','
        MIVOZ_TIMING(t_prefill_build_ms);
        MIVOZ_TIMING(t_prefill_forward_ms);
        MIVOZ_TIMING(t_prefill_graph_build_ms);
        MIVOZ_TIMING(t_prefill_graph_alloc_ms);
        MIVOZ_TIMING(t_prefill_compute_ms);
        MIVOZ_TIMING(t_prefill_data_ms);
        MIVOZ_TIMING(t_talker_forward_ms);
        MIVOZ_TIMING(t_talker_graph_build_ms);
        MIVOZ_TIMING(t_talker_graph_alloc_ms);
        MIVOZ_TIMING(t_talker_compute_ms);
        MIVOZ_TIMING(t_talker_data_ms);
        MIVOZ_TIMING(t_code_pred_ms);
        MIVOZ_TIMING(t_code_pred_graph_build_ms);
        MIVOZ_TIMING(t_code_pred_graph_alloc_ms);
        MIVOZ_TIMING(t_code_pred_compute_ms);
        MIVOZ_TIMING(t_code_pred_input_upload_ms);
        MIVOZ_TIMING(t_code_pred_logits_read_ms);
        MIVOZ_TIMING(t_code_pred_sched_reset_ms);
        MIVOZ_TIMING(t_code_pred_sampling_ms);
        MIVOZ_TIMING(t_code_pred_prefill_ms);
        MIVOZ_TIMING(t_code_pred_steps_ms);
        MIVOZ_TIMING(t_generate_total_ms);
#undef MIVOZ_TIMING
        out << "\"frames\":" << value.n_frames << '}';
        transformer_json = out.str();
    }
};

template<class Result> inline std::string result_json(const Result & result) {
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << '{'
        << "\"tokenizeMs\":" << result.t_tokenize_ms << ','
        << "\"encodeMs\":" << result.t_encode_ms << ','
        << "\"generateMs\":" << result.t_generate_ms << ','
        << "\"decodeMs\":" << result.t_decode_ms << ','
        << "\"decodeFrames\":" << result.decode_frames << ','
        << "\"decodeSamples\":" << result.decode_samples << ','
        << "\"decodeGraphBuildMs\":" << result.t_decode_graph_build_ms << ','
        << "\"decodeGraphAllocMs\":" << result.t_decode_graph_alloc_ms << ','
        << "\"decodeGraphComputeMs\":" << result.t_decode_graph_compute_ms << ','
        << "\"transformer\":" << transformer_json << '}';
    return out.str();
}
} // namespace mivoz_native_metrics
