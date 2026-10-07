#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort(false);

struct CbData {
    JNIEnv *env;
    jobject cb;
    jmethodID onSegment;
    jmethodID onProgress;
};

static void segment_cb(struct whisper_context *ctx, struct whisper_state * /*state*/, int n_new, void *user_data) {
    auto *d = static_cast<CbData *>(user_data);
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        const char *txt = whisper_full_get_segment_text(ctx, i);
        if (txt == nullptr) continue;
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const jsize len = (jsize) strlen(txt);
        jbyteArray arr = d->env->NewByteArray(len);
        d->env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(txt));
        d->env->CallVoidMethod(d->cb, d->onSegment, (jlong) t0, (jlong) t1, arr);
        d->env->DeleteLocalRef(arr);
        if (d->env->ExceptionCheck()) d->env->ExceptionClear();
    }
}

static void progress_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, int progress, void *user_data) {
    auto *d = static_cast<CbData *>(user_data);
    d->env->CallVoidMethod(d->cb, d->onProgress, (jint) progress);
    if (d->env->ExceptionCheck()) d->env->ExceptionClear();
}

static bool abort_cb(void * /*data*/) {
    return g_abort.load();
}

static bool encoder_begin_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, void * /*data*/) {
    return !g_abort.load();
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_haessentz_videoeditor_media_WhisperLib_initContext(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) LOGE("failed to load model");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_haessentz_videoeditor_media_WhisperLib_freeContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_haessentz_videoeditor_media_WhisperLib_setAbort(JNIEnv * /*env*/, jobject /*thiz*/, jboolean value) {
    g_abort.store(value == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_haessentz_videoeditor_media_WhisperLib_systemInfo(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jint JNICALL
Java_com_haessentz_videoeditor_media_WhisperLib_transcribe(JNIEnv *env, jobject /*thiz*/, jlong ptr,
                                                         jstring pcmPath, jstring language,
                                                         jint threads, jint maxLen, jobject callback) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return -100;

    // Read 16 kHz mono signed 16-bit little-endian PCM
    const char *path = env->GetStringUTFChars(pcmPath, nullptr);
    FILE *f = fopen(path, "rb");
    env->ReleaseStringUTFChars(pcmPath, path);
    if (f == nullptr) return -101;
    fseek(f, 0, SEEK_END);
    long bytes = ftell(f);
    fseek(f, 0, SEEK_SET);
    const size_t n = (size_t) (bytes / 2);
    std::vector<float> samples(n);
    std::vector<int16_t> chunk(65536);
    size_t pos = 0;
    while (pos < n) {
        size_t want = std::min(chunk.size(), n - pos);
        size_t got = fread(chunk.data(), sizeof(int16_t), want, f);
        if (got == 0) break;
        for (size_t i = 0; i < got; i++) samples[pos + i] = (float) chunk[i] / 32768.0f;
        pos += got;
    }
    fclose(f);
    samples.resize(pos);

    jclass cls = env->GetObjectClass(callback);
    CbData data{};
    data.env = env;
    data.cb = callback;
    data.onSegment = env->GetMethodID(cls, "onSegment", "(JJ[B)V");
    data.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = langStr.c_str();
    p.translate = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;
    if (maxLen > 0) {
        p.token_timestamps = true;
        p.max_len = maxLen;
        p.split_on_word = true;
    }
    p.new_segment_callback = segment_cb;
    p.new_segment_callback_user_data = &data;
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = &data;
    p.encoder_begin_callback = encoder_begin_cb;
    p.encoder_begin_callback_user_data = nullptr;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    LOGI("transcribing %zu samples with %d threads", samples.size(), threads);
    int r = whisper_full(ctx, p, samples.data(), (int) samples.size());
    return r;
}

}
