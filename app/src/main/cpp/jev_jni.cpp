// lyco-jev: on-device scorer for Jev-Style typed decisions (goal 1).
//
// Mirrors the reference "macjev-readout-v1" readout: for every option slot the
// score is logit(" yes") - logit(" no") at the token that ends that option's
// " ->" segment. Probabilities are softmax(scores / T) with T from
// readout_config.json (global 0.880); calibration happens in Kotlin.
//
// State/question/option strings are rendered in Kotlin and tokenised here, so
// this file has one job: decode once, read logits only at the slot positions.
// Nothing is ever generated.
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "JEVLOCAL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct JevLocal {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    std::mutex mu;
};

inline JevLocal *as(jlong h) { return reinterpret_cast<JevLocal *>(h); }

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

int tokenize(const JevLocal *j, const std::string &text, std::vector<llama_token> &out) {
    if (!j->vocab) return 0;
    // Special tokens disabled: text inside state/options can never act as a control token.
    int n = -llama_tokenize(j->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, false);
    if (n <= 0) return 0;
    out.resize((size_t) n);
    int got = llama_tokenize(j->vocab, text.c_str(), (int32_t) text.size(), out.data(), n, false, false);
    if (got < 0) return 0;
    out.resize((size_t) got);
    return got;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeBackendInit(JNIEnv *, jclass) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeLoad(JNIEnv *env, jclass, jstring jpath,
                                                 jint n_ctx, jint n_threads) {
    auto *j = new JevLocal();
    std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU only: the phone has no GPU path for this.
    j->model = llama_model_load_from_file(path.c_str(), mp);
    if (!j->model) {
        LOGE("model load failed: %s", path.c_str());
        delete j;
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    // Keep the logical batch at n_ctx but the micro-batch small: sizing n_ubatch
    // to the whole context makes the graph reserve far more than one question
    // needs, which dominated the first decode on device.
    cp.n_batch = (uint32_t) std::min(n_ctx, 1024);
    cp.n_ubatch = (uint32_t) std::min(n_ctx, 256);
    cp.n_threads = (int32_t) n_threads;
    cp.n_threads_batch = (int32_t) n_threads;
    // One logits row per option slot; libllama aborts when a decode asks for more
    // rows than this. 32 slots is far beyond the 7-question set.
    cp.n_outputs_max = 32;
    j->ctx = llama_init_from_model(j->model, cp);
    if (!j->ctx) {
        LOGE("context creation failed (n_ctx=%d)", (int) cp.n_ctx);
        llama_model_free(j->model);
        delete j;
        return 0;
    }
    j->vocab = llama_model_get_vocab(j->model);
    j->n_ctx = n_ctx;
    LOGI("model ready: n_ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(j);
}

JNIEXPORT void JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeFree(JNIEnv *, jclass, jlong h) {
    JevLocal *j = as(h);
    if (!j) return;
    if (j->ctx) llama_free(j->ctx);
    if (j->model) llama_model_free(j->model);
    delete j;
}

/** Token ids for one text segment (special tokens disabled). */
JNIEXPORT jintArray JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeTokenize(JNIEnv *env, jclass, jlong h, jstring jtext) {
    JevLocal *j = as(h);
    if (!j || !j->vocab) return nullptr;
    std::lock_guard<std::mutex> lock(j->mu);
    std::vector<llama_token> toks;
    tokenize(j, jstr(env, jtext), toks);
    jintArray out = env->NewIntArray((jsize) toks.size());
    if (out && !toks.empty()) env->SetIntArrayRegion(out, 0, (jsize) toks.size(), toks.data());
    return out;
}

/** Single token id for a marker such as " yes" / " no" / " ->"; -1 when it is not exactly one token. */
JNIEXPORT jint JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeTokenId(JNIEnv *env, jclass, jlong h, jstring jtext) {
    JevLocal *j = as(h);
    if (!j || !j->vocab) return -1;
    std::lock_guard<std::mutex> lock(j->mu);
    std::vector<llama_token> toks;
    if (tokenize(j, jstr(env, jtext), toks) != 1) return -1;
    return (jint) toks[0];
}

/**
 * One forward pass. Returns one score per entry of the slots array:
 * logit(" yes") - logit(" no") at that absolute token position.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeDecide(JNIEnv *env, jclass, jlong h,
                                                   jintArray jids, jintArray jslots,
                                                   jint yesId, jint noId) {
    JevLocal *j = as(h);
    if (!j || !j->ctx) return nullptr;
    std::lock_guard<std::mutex> lock(j->mu);

    const jsize n = env->GetArrayLength(jids);
    const jsize ns = env->GetArrayLength(jslots);
    if (n <= 0 || ns <= 0 || n > j->n_ctx) return nullptr;

    std::vector<llama_token> ids((size_t) n);
    std::vector<jint> slots((size_t) ns);
    env->GetIntArrayRegion(jids, 0, n, ids.data());
    env->GetIntArrayRegion(jslots, 0, ns, slots.data());

    // Logits only at the slot positions -- never the whole sequence.
    std::vector<jint> slotOf((size_t) n, -1);
    for (jsize s = 0; s < ns; ++s) {
        jint p = slots[(size_t) s];
        if (p < 0 || p >= n) return nullptr;
        slotOf[(size_t) p] = s;
    }

    llama_memory_clear(llama_get_memory(j->ctx), true);

    llama_batch batch = llama_batch_init((int32_t) n, 0, 1);
    for (jsize i = 0; i < n; ++i) {
        batch.token[i] = ids[(size_t) i];
        batch.pos[i] = (llama_pos) i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (slotOf[(size_t) i] >= 0) ? 1 : 0;
    }
    batch.n_tokens = (int32_t) n;

    int rc = llama_decode(j->ctx, batch);
    if (rc != 0) {
        LOGE("llama_decode failed: %d", rc);
        llama_batch_free(batch);
        return nullptr;
    }

    const int n_vocab = llama_vocab_n_tokens(j->vocab);
    if (yesId < 0 || noId < 0 || yesId >= n_vocab || noId >= n_vocab) {
        llama_batch_free(batch);
        return nullptr;
    }
    // llama_get_logits_ith takes the BATCH TOKEN INDEX, not the ordinal among
    // requested outputs: internally output_ids[batch_index] maps to the row that
    // holds that token's logits. Passing a running counter aborts inside
    // llama_context::get_logits_ith (invalid logits id).
    std::vector<jfloat> out((size_t) ns, 0.0f);
    for (jsize i = 0; i < n; ++i) {
        if (slotOf[(size_t) i] < 0) continue;
        const float *logits = llama_get_logits_ith(j->ctx, i);
        if (!logits) continue;
        out[(size_t) slotOf[(size_t) i]] = logits[yesId] - logits[noId];
    }
    llama_batch_free(batch);

    jfloatArray res = env->NewFloatArray(ns);
    if (res) env->SetFloatArrayRegion(res, 0, ns, out.data());
    return res;
}

/**
 * Render a system+user turn with the model's own chat template and tokenise it
 * with special tokens ENABLED.
 *
 * The judge path disables them on purpose (text inside state/options must never
 * act as a control token). A generative prompt is the opposite: it is made of
 * control tokens (<|im_start|> and friends), and tokenising those as literal
 * text produces a prompt the model has never seen.
 *
 * @param jskipThinking when true the assistant header is appended here together
 *   with an EMPTY think block. Qwen3.5 reasons by default, and this is the only
 *   thing that stopped it: asking in the prompt text ("/no_think", "不要思考")
 *   changed nothing - 120 sampled tokens were still all reasoning. With the empty
 *   block prefilled, the same prompt answers in 51 tokens.
 */
JNIEXPORT jintArray JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeChatPrompt(JNIEnv *env, jclass, jlong h,
                                                       jstring jsystem, jstring juser,
                                                       jboolean jskipThinking) {
    JevLocal *j = as(h);
    if (!j || !j->model || !j->vocab) return nullptr;
    std::lock_guard<std::mutex> lock(j->mu);

    const char *tmpl = llama_model_chat_template(j->model, nullptr);
    if (!tmpl) {
        LOGE("model carries no chat template");
        return nullptr;
    }

    const std::string sys = jstr(env, jsystem);
    const std::string usr = jstr(env, juser);
    llama_chat_message msgs[2] = {
        {"system", sys.c_str()},
        {"user", usr.c_str()},
    };

    // With the empty think block we must NOT let the template emit the assistant
    // header: it has to come after the block, not before it.
    const bool skipThinking = (jskipThinking == JNI_TRUE);
    const bool addAss = !skipThinking;
    const int extra = skipThinking ? 64 : 0;

    // A zero-length call only measures the rendered size.
    int need = llama_chat_apply_template(tmpl, msgs, 2, addAss, nullptr, 0);
    if (need <= 0) {
        LOGE("chat template measure failed: %d", need);
        return nullptr;
    }
    std::vector<char> buf((size_t) need + (size_t) extra + 1, 0);
    int wrote = llama_chat_apply_template(tmpl, msgs, 2, addAss, buf.data(), (int32_t) buf.size());
    if (wrote <= 0) return nullptr;

    std::string prompt(buf.data(), (size_t) wrote);
    if (skipThinking) prompt += "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    int n = -llama_tokenize(j->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> toks((size_t) n);
    int got = llama_tokenize(j->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, true, true);
    if (got < 0) return nullptr;
    toks.resize((size_t) got);

    jintArray out = env->NewIntArray((jsize) toks.size());
    if (out && !toks.empty()) env->SetIntArrayRegion(out, 0, (jsize) toks.size(), toks.data());
    return out;
}

/**
 * Sample a continuation from a prompt built by nativeChatPrompt.
 *
 * This needs a generative weight. Jev-Style-0.8B-Decision-v3 cannot do it: it was
 * trained down to a yes/no decision head, and a 160-token sample came back as
 * "yes no no no No yes no ..." - all 160 tokens were yes/no. That measurement is
 * why the drafting route carries a second, generative GGUF.
 */
JNIEXPORT jstring JNICALL
Java_com_jev_probe_jev_LocalJevNative_nativeGenerate(JNIEnv *env, jclass, jlong h,
                                                     jintArray jids, jint maxTokens,
                                                     jfloat temperature, jfloat topP) {
    JevLocal *j = as(h);
    if (!j || !j->ctx || !j->vocab) return nullptr;
    std::lock_guard<std::mutex> lock(j->mu);

    const jsize n = env->GetArrayLength(jids);
    if (n <= 0 || (int32_t) n >= j->n_ctx) return nullptr;

    std::vector<llama_token> ids((size_t) n);
    env->GetIntArrayRegion(jids, 0, n, ids.data());

    llama_memory_clear(llama_get_memory(j->ctx), true);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Prefill the whole prompt in one batch; llama_batch_get_one leaves pos null,
    // so the memory module assigns 0..n-1 and the loop below continues from n.
    llama_batch batch = llama_batch_get_one(ids.data(), (int32_t) n);
    if (llama_decode(j->ctx, batch) != 0) {
        LOGE("generate: prefill failed (%d tokens)", (int) n);
        llama_sampler_free(smpl);
        return nullptr;
    }

    std::string out;
    int n_past = (int) n;
    for (int i = 0; i < maxTokens; ++i) {
        if (n_past + 1 >= j->n_ctx) break;
        llama_token id = llama_sampler_sample(smpl, j->ctx, -1);
        if (llama_vocab_is_eog(j->vocab, id)) break;

        char buf[512];
        int len = llama_token_to_piece(j->vocab, id, buf, (int32_t) sizeof(buf), 0, true);
        if (len > 0) out.append(buf, (size_t) len);

        llama_batch nb = llama_batch_get_one(&id, 1);
        if (llama_decode(j->ctx, nb) != 0) {
            LOGE("generate: decode failed at token %d", i);
            break;
        }
        ++n_past;
    }

    llama_sampler_free(smpl);
    LOGI("generate: %d new tokens, %d chars", n_past - (int) n, (int) out.size());
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
