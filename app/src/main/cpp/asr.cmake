# Whisper's high-level implementation uses the exact same CPU GGML as Qwen.
# Do not add whisper/ggml: a second version would export conflicting symbols.
set(MIVOZ_WHISPER_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/whisper.cpp")
if (NOT EXISTS "${MIVOZ_WHISPER_ROOT}/src/whisper.cpp")
    message(FATAL_ERROR "Whisper sources missing. Run python scripts/prepare_asr.py before building.")
endif()
add_library(mivoz_whisper STATIC "${MIVOZ_WHISPER_ROOT}/src/whisper.cpp")
target_include_directories(mivoz_whisper PUBLIC "${MIVOZ_WHISPER_ROOT}/include" PRIVATE "${MIVOZ_WHISPER_ROOT}/src")
target_link_libraries(mivoz_whisper PUBLIC ggml ggml-base ggml-cpu Threads::Threads)
target_compile_options(mivoz_whisper PRIVATE -O3)
# Qwen's newer GGML removed this public padding constant. Whisper 1.8.2
# still pads its masks to 64; preserving that shape is harmless with flash off.
target_compile_definitions(mivoz_whisper PRIVATE NDEBUG GGML_KQ_MASK_PAD=64 WHISPER_VERSION="1.8.2")
add_library(mivoz_asr_jni SHARED "${CMAKE_CURRENT_LIST_DIR}/whisper_reference_jni.cpp")
target_link_libraries(mivoz_asr_jni PRIVATE mivoz_whisper log)
