// Ponte JNI verso whisper.cpp: carica il modello, trascrive l'audio (PCM float 16 kHz mono) e
// restituisce i segmenti come righe "inizio_ms<TAB>fine_ms<TAB>testo".
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <thread>

#ifdef WHISPER_ENABLED
#include "whisper.h"
#endif

#define TAG "SonaraWhisper"

static std::atomic<bool> g_abort{false};

#ifdef WHISPER_ENABLED
struct ProgressCtx {
    JNIEnv* env;
    jobject cb;
    jmethodID mid;
    int last;
};

static void on_progress(struct whisper_context*, struct whisper_state*, int progress, void* user) {
    auto* p = static_cast<ProgressCtx*>(user);
    if (!p || !p->cb || progress == p->last) return;
    p->last = progress;
    p->env->CallVoidMethod(p->cb, p->mid, (jint) progress);
}

static bool should_abort(void*) { return g_abort.load(); }
#endif

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_marco_mlplayer_ai_WhisperJni_available(JNIEnv*, jclass) {
#ifdef WHISPER_ENABLED
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL Java_com_marco_mlplayer_ai_WhisperJni_init(JNIEnv* env, jclass, jstring path) {
#ifdef WHISPER_ENABLED
    const char* p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_com_marco_mlplayer_ai_WhisperJni_free(JNIEnv*, jclass, jlong ctx) {
#ifdef WHISPER_ENABLED
    if (ctx) whisper_free(reinterpret_cast<whisper_context*>(ctx));
#endif
}

JNIEXPORT void JNICALL Java_com_marco_mlplayer_ai_WhisperJni_cancel(JNIEnv*, jclass) { g_abort.store(true); }

JNIEXPORT jstring JNICALL Java_com_marco_mlplayer_ai_WhisperJni_transcribe(
    JNIEnv* env, jclass, jlong handle, jfloatArray pcm, jstring lang, jint threads, jobject cb) {
#ifdef WHISPER_ENABLED
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) return env->NewStringUTF("");
    g_abort.store(false);

    jsize n = env->GetArrayLength(pcm);
    jfloat* data = env->GetFloatArrayElements(pcm, nullptr);
    const char* l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.language = language.c_str();     // "auto" = riconosce la lingua da solo
    wp.detect_language = false;
    wp.n_threads = threads > 0 ? threads : (int) std::max(2u, std::thread::hardware_concurrency() / 2);
    wp.no_context = true;               // ogni finestra parte da zero: meno ripetizioni inventate
    wp.token_timestamps = true;         // tempi per singola parola (karaoke)
    wp.suppress_nst = true;             // niente "[Musica]", "♪" e simili
    wp.greedy.best_of = 1;

    ProgressCtx pc{env, nullptr, nullptr, -1};
    if (cb) {
        jclass cls = env->GetObjectClass(cb);
        pc.cb = cb;
        pc.mid = env->GetMethodID(cls, "onProgress", "(I)V");
        wp.progress_callback = on_progress;
        wp.progress_callback_user_data = &pc;
    }
    wp.abort_callback = should_abort;
    wp.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, wp, data, (int) n);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full ha restituito %d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        std::string text = whisper_full_get_segment_text(ctx, i);
        for (auto& c : text) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
        long long t0 = whisper_full_get_segment_t0(ctx, i) * 10;   // centesimi di secondo -> ms
        long long t1 = whisper_full_get_segment_t1(ctx, i) * 10;

        // parole: i pezzi (token) che non iniziano con uno spazio si attaccano alla parola precedente
        std::string words, cur;
        long long ws = -1, we = -1;
        auto flush = [&]() {
            if (!cur.empty()) words += std::to_string(ws) + ":" + std::to_string(we) + ":" + cur + "\x1f";
            cur.clear();
            ws = -1;
        };
        int nt = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < nt; j++) {
            if (whisper_full_get_token_id(ctx, i, j) >= whisper_token_eot(ctx)) continue;   // token speciali e tempi
            const char* tx = whisper_full_get_token_text(ctx, i, j);
            if (!tx) continue;
            std::string tk(tx);
            for (auto& c : tk) if (c == '\t' || c == '\n' || c == '\r' || c == '\x1f') c = ' ';
            whisper_token_data d = whisper_full_get_token_data(ctx, i, j);
            bool starts = !tk.empty() && tk[0] == ' ';
            if (starts) flush();
            std::string core = starts ? tk.substr(1) : tk;
            if (core.empty()) continue;
            if (cur.empty()) ws = d.t0 * 10;
            cur += core;
            we = d.t1 * 10;
        }
        flush();
        out += std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + text + "\t" + words + "\n";
    }
    return env->NewStringUTF(out.c_str());
#else
    return env->NewStringUTF("");
#endif
}

}  // extern "C"
