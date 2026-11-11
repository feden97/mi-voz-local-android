#include <jni.h>
#include "whisper.h"

#include <algorithm>
#include <cmath>
#include <exception>
#include <memory>
#include <new>
#include <string>
#include <vector>

namespace {
void quiet_log(ggml_log_level, const char *, void *) { }

struct Callback {
    JavaVM * vm;
    JNIEnv * owner;
    jobject object;
    jmethodID is_cancelled;
    jmethodID progress;
    ~Callback() { if (object) owner->DeleteGlobalRef(object); }

    struct Env {
        JavaVM * vm;
        JNIEnv * env = nullptr;
        bool attached = false;
        explicit Env(JavaVM * value) : vm(value) {
            if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
                attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
            }
        }
        ~Env() { if (attached) vm->DetachCurrentThread(); }
    };

    bool cancelled() {
        Env current(vm);
        if (!current.env) return true;
        const bool result = current.env->CallBooleanMethod(object, is_cancelled) == JNI_TRUE;
        return current.env->ExceptionCheck() || result;
    }
    void report(int percent) {
        Env current(vm);
        if (current.env && !current.env->ExceptionCheck()) current.env->CallVoidMethod(object, progress, percent);
    }
};

bool abort_transcription(void * state) { return static_cast<Callback *>(state)->cancelled(); }
void report_progress(whisper_context *, whisper_state *, int progress, void * state) {
    static_cast<Callback *>(state)->report(progress);
}
bool begin_encoder(whisper_context *, whisper_state *, void * state) {
    return !static_cast<Callback *>(state)->cancelled();
}

void fail(JNIEnv * env, const char * type, const char * message) {
    if (!env->ExceptionCheck()) {
        jclass exception = env->FindClass(type);
        if (exception) env->ThrowNew(exception, message);
    }
}

struct UtfString {
    JNIEnv * env;
    jstring input;
    const char * data;
    UtfString(JNIEnv * e, jstring s) : env(e), input(s), data(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~UtfString() { if (data) env->ReleaseStringUTFChars(input, data); }
};
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_qwen_tts_android_data_ReferenceTranscriber_nativeTranscribe(
        JNIEnv * env, jobject, jstring model_path, jfloatArray pcm, jstring language,
        jint threads, jobject callback_object) {
    try {
    if (!model_path || !pcm || !language || !callback_object) {
        fail(env, "java/lang/IllegalArgumentException", "Faltan datos para transcribir la referencia.");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(pcm);
    if (count <= 0 || count > WHISPER_SAMPLE_RATE * 30 || threads < 1 || threads > 4) {
        fail(env, "java/lang/IllegalArgumentException", "La referencia debe durar entre 1 y 30 segundos.");
        return nullptr;
    }
    jclass callback_class = env->GetObjectClass(callback_object);
    Callback callback{};
    env->GetJavaVM(&callback.vm);
    callback.owner = env;
    callback.object = env->NewGlobalRef(callback_object);
    callback.is_cancelled = env->GetMethodID(callback_class, "isCancelled", "()Z");
    callback.progress = env->GetMethodID(callback_class, "progress", "(I)V");
    if (env->ExceptionCheck() || !callback.object || !callback.is_cancelled || !callback.progress) return nullptr;
    if (callback.cancelled()) {
        fail(env, "java/util/concurrent/CancellationException", "Transcripción cancelada");
        return nullptr;
    }
    UtfString path(env, model_path);
    UtfString lang(env, language);
    if (!path.data || !lang.data) return nullptr;
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(pcm, 0, count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    for (float sample : samples) {
        if (!std::isfinite(sample) || std::abs(sample) > 1.01f) {
            fail(env, "java/lang/IllegalArgumentException", "La referencia contiene muestras no válidas.");
            return nullptr;
        }
    }
    // No user transcript, reference content, paths, or recognized words are written to native logs.
    whisper_log_set(quiet_log, nullptr);
    auto context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;
    std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
        whisper_init_from_file_with_params(path.data, context_params), whisper_free);
    if (!context) {
        fail(env, "java/io/IOException", "No se pudo abrir el transcriptor local. Volvé a intentar.");
        return nullptr;
    }
    if (callback.cancelled()) {
        fail(env, "java/util/concurrent/CancellationException", "Transcripción cancelada");
        return nullptr;
    }
    auto params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.n_threads = threads;
    params.language = lang.data;
    params.translate = false;
    params.detect_language = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_nst = true;
    params.beam_search.beam_size = 3;
    params.progress_callback = report_progress;
    params.progress_callback_user_data = &callback;
    params.encoder_begin_callback = begin_encoder;
    params.encoder_begin_callback_user_data = &callback;
    params.abort_callback = abort_transcription;
    params.abort_callback_user_data = &callback;
    const int result = whisper_full(context.get(), params, samples.data(), count);
    if (callback.cancelled()) {
        fail(env, "java/util/concurrent/CancellationException", "Transcripción cancelada");
        return nullptr;
    }
    if (result != 0) {
        fail(env, "java/io/IOException", "No se pudo transcribir el audio. Podés escribirlo manualmente.");
        return nullptr;
    }
    std::string text;
    const int segments = whisper_full_n_segments(context.get());
    for (int i = 0; i < segments; ++i) {
        const char * segment = whisper_full_get_segment_text(context.get(), i);
        if (segment) text += segment;
        if (text.size() > 16000) {
            fail(env, "java/io/IOException", "La transcripción resultó demasiado larga. Podés escribirla manualmente.");
            return nullptr;
        }
    }
    auto output = env->NewByteArray(static_cast<jsize>(text.size()));
    if (output && !text.empty()) env->SetByteArrayRegion(output, 0, static_cast<jsize>(text.size()),
                                                       reinterpret_cast<const jbyte *>(text.data()));
    return output;
    } catch (const std::bad_alloc &) {
        fail(env, "java/lang/OutOfMemoryError", "No hay memoria suficiente para transcribir. Cerrá otras apps y volvé a intentar.");
        return nullptr;
    } catch (const std::exception &) {
        fail(env, "java/io/IOException", "No se pudo transcribir la referencia. Podés escribir el texto manualmente.");
        return nullptr;
    }
}
