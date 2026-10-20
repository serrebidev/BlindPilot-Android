#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
struct Session {
    llama_model *model = nullptr;
    std::atomic<bool> cancelled{false};
    std::atomic<jlong> turn{0};
    ~Session() { if (model) llama_model_free(model); }
};
std::mutex sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> next_id{1};
std::once_flag initialized;

std::shared_ptr<Session> session(jlong handle) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    auto found = sessions.find(handle);
    if (found == sessions.end()) throw std::runtime_error("The local model was unloaded.");
    return found->second;
}
void fail(JNIEnv *env, const std::exception &error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
void check(const Session &s) {
    if (s.cancelled.load()) throw std::runtime_error("Stopped");
}
std::string string(JNIEnv *env, jstring value) {
    jclass cls = env->FindClass("java/lang/String");
    jmethodID get_bytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, get_bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck() || !bytes) throw std::runtime_error("Cannot read prompt text.");
    std::string output(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, output.size(), reinterpret_cast<jbyte *>(output.data()));
    env->DeleteLocalRef(bytes);
    return output;
}
bool abort_decode(void *data) { return static_cast<Session *>(data)->cancelled.load(); }
bool load_progress(float, void *data) { return !abort_decode(data); }

// The upstream MIT-licensed JSON grammar, limited to an object for the device-action protocol.
const char *json_grammar = R"grammar(
root ::= object
value ::= object | array | string | number | ("true" | "false" | "null") ws
object ::= "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
array ::= "[" ws (value ("," ws value)*)? "]" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
number ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9] [1-9]{0,15})? ws
ws ::= | " " | "\n" [ \t]{0,20}
)grammar";

void decode(llama_context *ctx, llama_batch_ext *batch, const llama_token *tokens, int count, int position, Session &s) {
    check(s);
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        const auto index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = position + i;
        if (index < 0 || !llama_batch_ext_set_pos(batch, index, &pos)) throw std::runtime_error("Cannot build the local prompt batch.");
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
    const int status = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
    check(s);
    if (status != 0) throw std::runtime_error("Local inference failed. Reduce context size or unload other apps.");
}
}

extern "C" JNIEXPORT jlong JNICALL Java_org_blindpilot_android_local_NativeRuntime_create(JNIEnv *env, jobject) {
    try {
        std::call_once(initialized, [] { llama_backend_init(); });
        const jlong id = next_id.fetch_add(1);
        std::lock_guard<std::mutex> lock(sessions_mutex);
        sessions.emplace(id, std::make_shared<Session>());
        return id;
    } catch (const std::exception &error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_org_blindpilot_android_local_NativeRuntime_begin(JNIEnv *env, jobject, jlong handle, jlong turn) {
    try {
        auto s = session(handle);
        s->cancelled.store(false);
        s->turn.store(turn);
    } catch (const std::exception &error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL Java_org_blindpilot_android_local_NativeRuntime_cancel(JNIEnv *, jobject, jlong handle, jlong turn) {
    // Stale callbacks must neither touch freed pointers nor cancel a newer generation.
    std::lock_guard<std::mutex> lock(sessions_mutex);
    auto found = sessions.find(handle);
    if (found != sessions.end() && found->second->turn.load() == turn) found->second->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL Java_org_blindpilot_android_local_NativeRuntime_load(JNIEnv *env, jobject, jlong handle, jstring path) {
    try {
        auto s = session(handle);
        check(*s);
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.check_tensors = true;
        params.progress_callback = load_progress;
        params.progress_callback_user_data = s.get();
        auto model = llama_model_load_from_file(string(env, path).c_str(), params);
        if (model && s->cancelled.load()) { llama_model_free(model); check(*s); }
        if (!model) throw std::runtime_error("Cannot load this GGUF model. It may be incomplete, unsupported, or too large for available memory.");
        if (s->model) llama_model_free(s->model);
        s->model = model;
    } catch (const std::exception &error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL Java_org_blindpilot_android_local_NativeRuntime_generate(
    JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jint context_size,
    jint max_tokens, jfloat temperature, jboolean json, jobject callback) {
    try {
        auto s = session(handle);
        check(*s);
        if (!s->model) throw std::runtime_error("Load a local model first.");
        if (llama_model_has_encoder(s->model) || !llama_model_has_decoder(s->model)) throw std::runtime_error("This runtime requires a decoder-only chat GGUF model.");
        if (context_size > llama_model_n_ctx_train(s->model)) throw std::runtime_error("The selected context size exceeds this model's trained context limit.");
        const char *tmpl = llama_model_chat_template(s->model, nullptr);
        if (!tmpl) throw std::runtime_error("This GGUF has no conversation template. Import an instruction/chat model with an embedded supported template.");
        const int count = env->GetArrayLength(roles);
        std::vector<std::string> role_text(count), content_text(count);
        std::vector<llama_chat_message> messages(count);
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
            role_text[i] = string(env, role);
            content_text[i] = string(env, content);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
            messages[i] = {role_text[i].c_str(), content_text[i].c_str()};
        }
        int length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
        if (length <= 0) throw std::runtime_error("This model's conversation template is not supported by the embedded runtime.");
        std::vector<char> formatted(length + 1);
        length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, formatted.data(), formatted.size());
        if (length <= 0) throw std::runtime_error("Cannot format this conversation for the selected model.");
        const auto vocab = llama_model_get_vocab(s->model);
        const int token_count = -llama_tokenize(vocab, formatted.data(), length, nullptr, 0, true, true);
        if (token_count < 1 || token_count + max_tokens > context_size) throw std::runtime_error("Conversation exceeds the local context limit. Start a new conversation, reduce maximum output tokens, or increase context size.");
        std::vector<llama_token> tokens(token_count);
        if (llama_tokenize(vocab, formatted.data(), length, tokens.data(), tokens.size(), true, true) < 0) throw std::runtime_error("Cannot tokenize this conversation.");
        auto params = llama_context_default_params();
        params.n_ctx = context_size;
        params.n_batch = std::min(256, context_size);
        params.n_ubatch = params.n_batch;
        params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        params.n_threads_batch = params.n_threads;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(s->model, params), llama_free);
        if (!ctx) throw std::runtime_error("Cannot allocate the local context. Reduce context size.");
        llama_set_abort_callback(ctx.get(), abort_decode, s.get());
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
        if (!batch) throw std::runtime_error("Cannot allocate a prompt batch.");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Cannot allocate a sampler.");
        if (json) {
            auto grammar = llama_sampler_init_grammar(vocab, json_grammar, "root");
            if (!grammar) throw std::runtime_error("The JSON output grammar could not be initialized.");
            llama_sampler_chain_add(sampler.get(), grammar);
        }
        if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.95f, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        for (int pos = 0; pos < token_count; pos += params.n_batch)
            decode(ctx.get(), batch.get(), tokens.data() + pos, std::min(int(params.n_batch), token_count - pos), pos, *s);
        auto callback_class = env->GetObjectClass(callback);
        const auto token_callback = env->GetMethodID(callback_class, "token", "([B)V");
        env->DeleteLocalRef(callback_class);
        if (!token_callback) throw std::runtime_error("Cannot stream local model output.");
        int position = token_count;
        for (int generated = 0; generated < max_tokens; ++generated) {
            check(*s);
            const auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (size < 0) throw std::runtime_error("Cannot decode a generated token.");
            auto bytes = env->NewByteArray(size);
            if (!bytes) throw std::runtime_error("Not enough memory for local output.");
            env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(piece.data()));
            env->CallVoidMethod(callback, token_callback, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) throw std::runtime_error("Local output was interrupted.");
            if (generated + 1 < max_tokens) decode(ctx.get(), batch.get(), &token, 1, position++, *s);
        }
    } catch (const std::exception &error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL Java_org_blindpilot_android_local_NativeRuntime_destroy(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    sessions.erase(handle);
}
