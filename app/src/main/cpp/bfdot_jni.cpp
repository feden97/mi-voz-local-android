#include <jni.h>

extern "C" bool mivoz_configure_bfdot(bool requested);
extern "C" bool mivoz_take_bfdot_used();

extern "C" JNIEXPORT jboolean JNICALL
Java_com_qwen_tts_studio_engine_QwenEngine_nativeConfigureBfdot(JNIEnv *, jobject, jboolean requested) {
    return mivoz_configure_bfdot(requested == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_qwen_tts_studio_engine_QwenEngine_nativeTakeBfdotUsed(JNIEnv *, jobject) {
    return mivoz_take_bfdot_used() ? JNI_TRUE : JNI_FALSE;
}
