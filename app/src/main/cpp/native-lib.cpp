#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <cstdlib>
#include <limits>
#include <csignal>
#include <cstring>

#include "llama.h"

#define LOG_TAG "native-lib"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static bool g_backend_inited = false;
static int32_t g_n_past = 0;
static std::atomic<bool> g_is_generating{false};

struct GeneratorGuard {
    GeneratorGuard() : acquired(false) {
        bool expected = false;
        if (g_is_generating.compare_exchange_strong(expected, true,
                std::memory_order_acq_rel, std::memory_order_acquire)) {
            acquired = true;
        }
    }
    ~GeneratorGuard() {
        if (acquired) {
            g_is_generating.store(false, std::memory_order_release);
        }
    }
    GeneratorGuard(const GeneratorGuard&) = delete;
    GeneratorGuard& operator=(const GeneratorGuard&) = delete;
    bool acquired;
};

static void safe_free() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_n_past = 0;
}

static void android_safe_free() {
    std::lock_guard<std::mutex> lock(g_mutex);
    safe_free();
    if (g_backend_inited) {
        llama_backend_free();
        g_backend_inited = false;
    }
    LOGI("All llama resources freed, g_n_past reset to 0");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_initModel(
        JNIEnv* env,
        jobject thiz,
        jstring model_path,
        jint threads,
        jint ctxSize,
        jboolean useGpu
) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }

    int32_t n_threads = (int32_t)threads;
    int32_t n_ctx = (int32_t)ctxSize;
    bool gpu_enabled = (bool)useGpu;

    LOGI("Loading model from: %s (threads=%d, ctx=%d, gpu=%s)",
         path, n_threads, n_ctx, gpu_enabled ? "true" : "false");

    if (g_is_generating.load(std::memory_order_acquire)) {
        LOGW("Cannot init model while generation in progress!");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
        LOGI("Llama backend initialized");
    }

    safe_free();
    g_n_past = 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.use_mlock = false;
    mparams.n_gpu_layers = gpu_enabled ? 81 : 0;

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model from file");
        return JNI_FALSE;
    }

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (vocab) {
        auto v_type = llama_vocab_type(vocab);
        int32_t vocab_size = llama_vocab_n_tokens(vocab);
        LOGI("Model loaded successfully, vocab type: %d, vocab size: %d, gpu_layers=%d",
             v_type, vocab_size, mparams.n_gpu_layers);
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)n_ctx;
    cparams.n_batch = std::min((uint32_t)n_ctx, (uint32_t)2048);
    cparams.n_ubatch = 512;
    cparams.offload_kqv = gpu_enabled;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    LOGI("Context params: n_ctx=%u, n_batch=%u, n_threads=%d, offload_kqv=%s",
         cparams.n_ctx, cparams.n_batch, cparams.n_threads,
         gpu_enabled ? "true" : "false");

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_n_past = 0;
    LOGI("Context created successfully, n_ctx: %u, g_n_past: %d", llama_n_ctx(g_ctx), g_n_past);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_isModelLoaded(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_freeModel(JNIEnv* env, jobject thiz) {
    android_safe_free();
    LOGI("Model freed via JNI");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_clearCache(JNIEnv* env, jobject thiz) {
    if (g_is_generating.load(std::memory_order_acquire)) {
        LOGW("Generation in progress. Reset denied!");
        return;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx && g_model) {
        llama_free(g_ctx);

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 2048;
        cparams.n_batch = 2048;
        cparams.n_ubatch = 512;
        cparams.offload_kqv = false;
        cparams.n_threads = 4;
        cparams.n_threads_batch = 4;

        g_ctx = llama_init_from_model(g_model, cparams);

        g_n_past = 0;
        LOGI("Context completely recreated, memory safely reset! (CPU only, 4 threads)");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_getModelInfo(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model) {
        return env->NewStringUTF("No model loaded");
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int32_t vocab_size = vocab ? llama_vocab_n_tokens(vocab) : 0;

    std::string info = "Model: ";
    info += model_desc;
    info += "\n";
    info += "Vocabulary size: " + std::to_string(vocab_size) + "\n";
    info += "Context size: " + std::to_string(llama_n_ctx(g_ctx)) + "\n";
    info += "n_past: " + std::to_string(g_n_past) + "\n";

    return env->NewStringUTF(info.c_str());
}

static std::vector<llama_token> tokenize_impl(const llama_vocab* vocab, const std::string& text) {
    std::vector<llama_token> tokens(text.size() + 8, 0);

    int32_t n_tokens = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                                        tokens.data(), (int32_t)tokens.size(), true, true);

    if (n_tokens < 0) {
        int32_t needed = -n_tokens;
        tokens.assign(needed, 0);
        n_tokens = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                                   tokens.data(), (int32_t)tokens.size(), true, true);
    }

    if (n_tokens <= 0) {
        return {};
    }

    tokens.resize(n_tokens);
    return tokens;
}

static std::string detokenize_impl(const llama_vocab* vocab, llama_token token) {
    char buf[256] = {0};
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf) - 1, 0, true);

    if (n < 0) {
        return {};
    }

    return std::string(buf, n);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_generateResponse(
        JNIEnv* env,
        jobject thiz,
        jstring prompt_,
        jobject callback
) {
    GeneratorGuard guard;
    if (!guard.acquired) {
        jclass cb_cls = env->GetObjectClass(callback);
        jmethodID mid_on_error = env->GetMethodID(cb_cls, "onError", "(Ljava/lang/String;)V");
        if (mid_on_error) {
            jstring jmsg = env->NewStringUTF("Engine Busy");
            env->CallVoidMethod(callback, mid_on_error, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        LOGW("generateResponse rejected: Engine busy!");
        return;
    }

    jclass cb_cls = env->GetObjectClass(callback);

    jmethodID mid_on_token = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID mid_on_complete = env->GetMethodID(cb_cls, "onComplete", "()V");
    jmethodID mid_on_error = env->GetMethodID(cb_cls, "onError", "(Ljava/lang/String;)V");

    auto call_error = [&](const char* msg) {
        if (mid_on_error) {
            jstring jmsg = env->NewStringUTF(msg);
            env->CallVoidMethod(callback, mid_on_error, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        LOGE("Generation error: %s", msg);
    };

    if (!mid_on_token || !mid_on_complete) {
        call_error("Callback methods not found");
        return;
    }

    std::string prompt;
    {
        const char* p = env->GetStringUTFChars(prompt_, nullptr);
        if (!p) {
            call_error("Failed to read prompt");
            return;
        }
        prompt = p;
        env->ReleaseStringUTFChars(prompt_, p);
    }

    if (prompt.empty()) {
        call_error("Empty prompt");
        return;
    }

    llama_model* model = nullptr;
    llama_context* ctx = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        model = g_model;
        ctx = g_ctx;
    }

    if (!model) {
        call_error("Model not loaded");
        return;
    }
    if (!ctx) {
        call_error("Context not initialized");
        return;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) {
        call_error("Failed to get vocabulary");
        return;
    }

    auto tokens = tokenize_impl(vocab, prompt);
    if (tokens.empty()) {
        call_error("Tokenization failed");
        return;
    }

    const int32_t n_tokens = (int32_t)tokens.size();
    LOGI("Prompt tokens: %d, n_past: %d, decoding in bulk...", n_tokens, g_n_past);

    if (n_tokens > 2048) {
        call_error("Prompt length exceeds max batch size (2048).");
        return;
    }

    std::vector<llama_pos> batch_pos(n_tokens);
    std::vector<int32_t> batch_n_seq_id(n_tokens, 1);
    std::vector<llama_seq_id> batch_seq_id_val(n_tokens, 0);
    std::vector<llama_seq_id*> batch_seq_id_ptr(n_tokens);
    std::vector<int8_t> batch_logits(n_tokens, 0);

    for (int32_t i = 0; i < n_tokens; i++) {
        batch_pos[i] = g_n_past + i;
        batch_seq_id_ptr[i] = &batch_seq_id_val[i];
    }
    batch_logits.back() = 1;

    llama_batch batch = {0};
    batch.n_tokens = n_tokens;
    batch.token = tokens.data();
    batch.embd = nullptr;
    batch.pos = batch_pos.data();
    batch.n_seq_id = batch_n_seq_id.data();
    batch.seq_id = batch_seq_id_ptr.data();
    batch.logits = batch_logits.data();

    if (llama_decode(ctx, batch) != 0) {
        call_error("Prompt decode failed during batch processing");
        return;
    }

    int32_t current_n_past = g_n_past + n_tokens;

    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(12345));

    const int max_tokens = 512;
    int tokens_generated = 0;

    for (int i = 0; i < max_tokens; i++) {
        LOGI("Generating token index: %d", i);

        llama_token new_token = llama_sampler_sample(chain, ctx, -1);

        if (new_token < 0) {
            LOGE("Invalid token (id=%d) at index %d", new_token, i);
            llama_sampler_free(chain);
            call_error("Invalid token generated");
            return;
        }

        if (llama_vocab_is_eog(vocab, new_token) || new_token == 151645) {
            LOGI("Hit stop token (EOG=%d or im_end=151645, got=%d), ending generation.", new_token, new_token);
            break;
        }

        std::string piece = detokenize_impl(vocab, new_token);
        if (!piece.empty() && mid_on_token) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, mid_on_token, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        llama_pos pos_val = current_n_past;
        int32_t n_seq_id_val = 1;
        llama_seq_id seq_id_val = 0;
        llama_seq_id* seq_id_ptr = &seq_id_val;
        int8_t logits_val = 1;

        llama_batch out_batch = {0};
        out_batch.n_tokens = 1;
        out_batch.token = &new_token;
        out_batch.embd = nullptr;
        out_batch.pos = &pos_val;
        out_batch.n_seq_id = &n_seq_id_val;
        out_batch.seq_id = &seq_id_ptr;
        out_batch.logits = &logits_val;

        if (llama_decode(ctx, out_batch) != 0) {
            llama_sampler_free(chain);
            call_error("Decode failed");
            return;
        }

        current_n_past++;
        tokens_generated++;

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_n_past = current_n_past;
        }

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGW("JNI exception during generation at token %d", i);
            break;
        }
    }

    llama_sampler_free(chain);

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_n_past = current_n_past;
    }

    LOGI("Generation complete, %d tokens generated, final n_past: %d", tokens_generated, current_n_past);

    if (mid_on_complete) {
        env->CallVoidMethod(callback, mid_on_complete);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_iphone_huchenfeng_LlamaEngine_generateResponseSync(JNIEnv* env, jobject thiz, jstring prompt_) {
    GeneratorGuard guard;
    if (!guard.acquired) {
        return env->NewStringUTF("Error: Engine busy");
    }

    std::string prompt;
    {
        const char* p = env->GetStringUTFChars(prompt_, nullptr);
        if (!p) {
            return env->NewStringUTF("Error: Failed to read prompt");
        }
        prompt = p;
        env->ReleaseStringUTFChars(prompt_, p);
    }

    if (prompt.empty()) {
        return env->NewStringUTF("");
    }

    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    int32_t n_past = 0;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        model = g_model;
        ctx = g_ctx;
        n_past = g_n_past;
    }

    if (!model || !ctx) {
        return env->NewStringUTF("Error: Model or context not initialized");
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) {
        return env->NewStringUTF("Error: No vocabulary");
    }

    auto tokens = tokenize_impl(vocab, prompt);
    if (tokens.empty()) {
        return env->NewStringUTF("Error: Tokenization failed");
    }

    const int32_t n_tokens = (int32_t)tokens.size();
    if (n_tokens > 2048) {
        return env->NewStringUTF("Error: Prompt too long (exceeds 2048 tokens)");
    }

    std::vector<llama_pos> batch_pos(n_tokens);
    std::vector<int32_t> batch_n_seq_id(n_tokens, 1);
    std::vector<llama_seq_id> batch_seq_id_val(n_tokens, 0);
    std::vector<llama_seq_id*> batch_seq_id_ptr(n_tokens);
    std::vector<int8_t> batch_logits(n_tokens, 0);

    for (int32_t i = 0; i < n_tokens; i++) {
        batch_pos[i] = n_past + i;
        batch_seq_id_ptr[i] = &batch_seq_id_val[i];
    }
    batch_logits.back() = 1;

    llama_batch batch = {0};
    batch.n_tokens = n_tokens;
    batch.token = tokens.data();
    batch.embd = nullptr;
    batch.pos = batch_pos.data();
    batch.n_seq_id = batch_n_seq_id.data();
    batch.seq_id = batch_seq_id_ptr.data();
    batch.logits = batch_logits.data();

    if (llama_decode(ctx, batch) != 0) {
        return env->NewStringUTF("Error: Prompt decode failed");
    }

    int32_t current_n_past = n_past + n_tokens;

    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(12345));

    std::string result;
    const int max_tokens = 256;

    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sampler_sample(chain, ctx, -1);

        if (new_token < 0) {
            LOGE("Invalid token (id=%d) at index %d", new_token, i);
            llama_sampler_free(chain);
            return env->NewStringUTF(result.c_str());
        }

        if (llama_vocab_is_eog(vocab, new_token) || new_token == 151645) {
            LOGI("Hit stop token (EOG=%d or im_end=151645, got=%d), ending generation.", new_token, new_token);
            break;
        }

        result += detokenize_impl(vocab, new_token);

        llama_pos pos_val = current_n_past;
        int32_t n_seq_id_val = 1;
        llama_seq_id seq_id_val = 0;
        llama_seq_id* seq_id_ptr = &seq_id_val;
        int8_t logits_val = 1;

        llama_batch out_batch = {0};
        out_batch.n_tokens = 1;
        out_batch.token = &new_token;
        out_batch.embd = nullptr;
        out_batch.pos = &pos_val;
        out_batch.n_seq_id = &n_seq_id_val;
        out_batch.seq_id = &seq_id_ptr;
        out_batch.logits = &logits_val;

        if (llama_decode(ctx, out_batch) != 0) {
            llama_sampler_free(chain);
            return env->NewStringUTF(result.c_str());
        }

        current_n_past++;

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_n_past = current_n_past;
        }
    }

    llama_sampler_free(chain);

    return env->NewStringUTF(result.c_str());
}
