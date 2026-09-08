// ---------------------------------------------------------------------------
// JobMaker - pont JNI minimal vers llama.cpp
//
// Expose juste ce dont l'application a besoin :
//   - charger / liberer un modele GGUF
//   - appliquer le gabarit de conversation embarque dans le modele
//   - generer token par token (pour l'affichage en direct et l'annulation)
//
// Une session = un modele + un contexte + un echantillonneur. L'application
// n'en garde qu'une seule vivante a la fois : un modele 4B quantifie occupe
// deja ~2,5 Go de RAM.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "JobMakerLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Les symboles JNI doivent rester exportes malgre -fvisibility=hidden.
#define JNI_FN(ret) extern "C" JNIEXPORT ret JNICALL

namespace {

// Codes de retour partages avec LlamaBridge.kt. nativeBeginGenerate renvoie le
// nombre de tokens du prompt quand tout va bien, donc seules les valeurs
// negatives sont des erreurs.
constexpr jint ERR_NO_SESSION     = -1;
constexpr jint ERR_PROMPT_TOO_LONG = -2;
constexpr jint ERR_DECODE         = -3;
constexpr jint ERR_TOKENIZE       = -4;

struct Session {
    llama_model*        model   = nullptr;
    llama_context*      ctx     = nullptr;
    const llama_vocab*  vocab   = nullptr;
    llama_sampler*      sampler = nullptr;

    int n_ctx      = 0;
    int n_past     = 0;   // tokens deja presents dans le cache KV
    int n_emitted  = 0;   // tokens generes pour la requete en cours
    int max_tokens = 0;
    bool generating = false;

    // Lecture du prompt, avancee morceau par morceau depuis Kotlin plutot qu'en
    // un seul appel bloquant : c'est la phase la plus longue sur un telephone,
    // et il faut pouvoir l'afficher et l'interrompre.
    std::vector<llama_token> prompt;
    size_t prompt_lu = 0;

    // Un token peut couper une sequence UTF-8 au milieu (accents, emoji).
    // On garde les octets incomplets ici jusqu'a pouvoir former du texte valide.
    std::string utf8_tail;

    std::mutex mu;
};

bool g_backend_ready = false;
std::mutex g_backend_mu;

void log_bridge(ggml_log_level level, const char* text, void* /*user*/) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("%s", text); break;
        default:                   break;  // le reste est bien trop verbeux
    }
}

void ensure_backend() {
    std::lock_guard<std::mutex> lock(g_backend_mu);
    if (g_backend_ready) return;
    llama_log_set(log_bridge, nullptr);
    llama_backend_init();
    g_backend_ready = true;
}

Session* as_session(jlong handle) {
    return handle == 0 ? nullptr : reinterpret_cast<Session*>(handle);
}

std::string to_utf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* raw = env->GetStringUTFChars(s, nullptr);
    std::string out = raw ? raw : "";
    if (raw) env->ReleaseStringUTFChars(s, raw);
    return out;
}

// Longueur du plus long prefixe d'octets formant de l'UTF-8 complet.
size_t utf8_complete_prefix(const std::string& s) {
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len;
        if      ((c & 0x80) == 0x00) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else                          len = 1;  // octet invalide : on l'avale
        if (i + len > s.size()) break;
        i += len;
    }
    return i;
}

std::vector<llama_token> tokenize(const llama_vocab* vocab,
                                  const std::string& text,
                                  bool add_special) {
    // Premier appel a vide pour connaitre la taille reelle (retour negatif).
    int needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                 nullptr, 0, add_special, /*parse_special=*/true);
    if (needed <= 0) return {};
    std::vector<llama_token> tokens(needed);
    const int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                 tokens.data(), needed, add_special, /*parse_special=*/true);
    if (n < 0) return {};
    tokens.resize(n);
    return tokens;
}

std::string piece_of(const llama_vocab* vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n >= 0) return std::string(buf, n);
    // Piece plus longue que le tampon : on recommence avec la bonne taille.
    std::string big(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
    if (n < 0) return {};
    big.resize(n);
    return big;
}

// Taille d'un lot de lecture du prompt. Doit rester <= n_batch du contexte.
constexpr int kLotPrompt = 256;

void build_sampler(Session* s, float temp, float top_p, int top_k,
                   float repeat_penalty, int repeat_last_n, uint32_t seed) {
    if (s->sampler != nullptr) {
        llama_sampler_free(s->sampler);
        s->sampler = nullptr;
    }
    auto params = llama_sampler_chain_default_params();
    params.no_perf = true;
    s->sampler = llama_sampler_chain_init(params);

    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(
            s->sampler,
            llama_sampler_init_penalties(repeat_last_n, repeat_penalty, 0.0f, 0.0f));
    }

    if (temp <= 0.0f) {
        // Deterministe : indispensable pour les etapes qui doivent rendre du JSON.
        llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy());
        return;
    }

    if (top_k > 0)  llama_sampler_chain_add(s->sampler, llama_sampler_init_top_k(top_k));
    if (top_p < 1.0f) llama_sampler_chain_add(s->sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(s->sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s->sampler, llama_sampler_init_dist(seed));
}

void clear_kv(Session* s) {
    llama_memory_clear(llama_get_memory(s->ctx), /*data=*/true);
    s->n_past = 0;
}

}  // namespace

// ---------------------------------------------------------------------------
// Chargement / liberation
// ---------------------------------------------------------------------------

JNI_FN(jlong)
Java_com_jobmaker_llm_LlamaBridge_nativeLoad(JNIEnv* env, jobject /*thiz*/,
                                             jstring jpath, jint n_ctx,
                                             jint n_threads, jint n_gpu_layers,
                                             jboolean use_mmap) {
    ensure_backend();
    const std::string path = to_utf8(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;   // ignore si aucun backend GPU n'est compile
    mp.use_mlock    = false;          // mlock ferait tuer l'app par Android

    // use_mmap = true : les poids restent des pages du fichier, chargees a la
    // demande. Rapide a ouvrir, mais Android peut les evincer sous pression
    // memoire et il faut alors les relire depuis le stockage -- ce qui, sur un
    // modele de 2,5 Go relu a chaque token, effondre la vitesse.
    // use_mmap = false : tout est copie en memoire anonyme une fois pour
    // toutes. Ouverture plus lente, debit ensuite constant.
    mp.use_mmap     = (use_mmap == JNI_TRUE);

    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        LOGE("Chargement impossible : %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = static_cast<uint32_t>(n_ctx);
    cp.n_batch   = 256;
    cp.n_ubatch  = 256;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf   = true;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("Creation du contexte impossible (n_ctx=%d)", n_ctx);
        llama_model_free(model);
        return 0;
    }

    auto* s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = static_cast<int>(llama_n_ctx(ctx));

    LOGI("Modele charge : %s (n_ctx=%d, threads=%d, mmap=%d)",
         path.c_str(), s->n_ctx, n_threads, mp.use_mmap ? 1 : 0);
    return reinterpret_cast<jlong>(s);
}

JNI_FN(void)
Java_com_jobmaker_llm_LlamaBridge_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = as_session(handle);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->mu);
        if (s->sampler) llama_sampler_free(s->sampler);
        if (s->ctx)     llama_free(s->ctx);
        if (s->model)   llama_model_free(s->model);
    }
    delete s;
    LOGI("Session liberee");
}

// ---------------------------------------------------------------------------
// Introspection
// ---------------------------------------------------------------------------

JNI_FN(jint)
Java_com_jobmaker_llm_LlamaBridge_nativeContextSize(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = as_session(handle);
    return s == nullptr ? 0 : s->n_ctx;
}

JNI_FN(jint)
Java_com_jobmaker_llm_LlamaBridge_nativeTokenCount(JNIEnv* env, jobject /*thiz*/,
                                                   jlong handle, jstring jtext) {
    Session* s = as_session(handle);
    if (s == nullptr) return ERR_NO_SESSION;
    const std::string text = to_utf8(env, jtext);
    return static_cast<jint>(tokenize(s->vocab, text, /*add_special=*/false).size());
}

JNI_FN(jstring)
Java_com_jobmaker_llm_LlamaBridge_nativeDescribe(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Session* s = as_session(handle);
    if (s == nullptr) return nullptr;
    char buf[512];
    llama_model_desc(s->model, buf, sizeof(buf));
    const uint64_t params = llama_model_n_params(s->model);
    const uint64_t size   = llama_model_size(s->model);
    char out[768];
    snprintf(out, sizeof(out), "%s | %.2f Mds parametres | %.2f Go | contexte %d",
             buf, params / 1e9, size / 1e9, s->n_ctx);
    return env->NewStringUTF(out);
}

/**
 * Applique le gabarit de conversation embarque dans le GGUF. Retourne null si
 * le modele n'en declare pas ou si llama.cpp ne sait pas le rendre : Kotlin
 * bascule alors sur un gabarit ChatML generique.
 */
JNI_FN(jstring)
Java_com_jobmaker_llm_LlamaBridge_nativeApplyChatTemplate(JNIEnv* env, jobject /*thiz*/,
                                                          jlong handle,
                                                          jobjectArray jroles,
                                                          jobjectArray jcontents,
                                                          jboolean add_assistant) {
    Session* s = as_session(handle);
    if (s == nullptr) return nullptr;

    const char* tmpl = llama_model_chat_template(s->model, /*name=*/nullptr);
    if (tmpl == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) {
        roles[i]    = to_utf8(env, static_cast<jstring>(env->GetObjectArrayElement(jroles, i)));
        contents[i] = to_utf8(env, static_cast<jstring>(env->GetObjectArrayElement(jcontents, i)));
        msgs[i].role    = roles[i].c_str();
        msgs[i].content = contents[i].c_str();
    }

    std::vector<char> buf(8192);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                            add_assistant == JNI_TRUE,
                                            buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                        add_assistant == JNI_TRUE,
                                        buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) {
        LOGW("Gabarit de conversation non supporte, repli sur ChatML");
        return nullptr;
    }
    return env->NewStringUTF(std::string(buf.data(), len).c_str());
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

JNI_FN(jint)
Java_com_jobmaker_llm_LlamaBridge_nativeBeginGenerate(JNIEnv* env, jobject /*thiz*/,
                                                      jlong handle, jstring jprompt,
                                                      jint max_tokens, jfloat temp,
                                                      jfloat top_p, jint top_k,
                                                      jfloat repeat_penalty,
                                                      jint repeat_last_n, jint seed) {
    Session* s = as_session(handle);
    if (s == nullptr) return ERR_NO_SESSION;
    std::lock_guard<std::mutex> lock(s->mu);

    const std::string prompt = to_utf8(env, jprompt);

    // Le prompt est deja rendu par le gabarit : pas de BOS supplementaire si le
    // gabarit en contient un. add_special=true laisse llama.cpp trancher selon
    // les metadonnees du modele.
    std::vector<llama_token> tokens = tokenize(s->vocab, prompt, /*add_special=*/true);
    if (tokens.empty()) return ERR_TOKENIZE;

    if (static_cast<int>(tokens.size()) + max_tokens + 8 > s->n_ctx) {
        LOGE("Prompt trop long : %zu tokens + %d generes > contexte %d",
             tokens.size(), max_tokens, s->n_ctx);
        return ERR_PROMPT_TOO_LONG;
    }

    clear_kv(s);
    build_sampler(s, temp, top_p, top_k, repeat_penalty, repeat_last_n,
                  static_cast<uint32_t>(seed));

    s->prompt     = std::move(tokens);
    s->prompt_lu  = 0;
    s->n_emitted  = 0;
    s->max_tokens = max_tokens;
    s->generating = false;   // vrai seulement quand le prompt est entierement lu
    s->utf8_tail.clear();

    // Retour positif = nombre de tokens que le prompt va demander de lire.
    // L'interface s'en sert pour afficher un avancement : c'est la phase
    // pendant laquelle rien ne s'ecrit et ou l'on croit l'application figee.
    return static_cast<jint>(s->prompt.size());
}

/**
 * Lit le lot suivant du prompt.
 *
 * Retourne le nombre de tokens lus (> 0), 0 quand le prompt est entierement
 * lu et que la generation peut commencer, ou un code d'erreur negatif.
 *
 * Decouper permet a l'appelant d'afficher l'avancement et d'abandonner : un
 * llama_decode sur 2000 tokens d'un coup est un appel bloquant de plusieurs
 * dizaines de secondes qu'aucun bouton ne peut interrompre.
 */
JNI_FN(jint)
Java_com_jobmaker_llm_LlamaBridge_nativeLirePromptSuivant(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    Session* s = as_session(handle);
    if (s == nullptr) return ERR_NO_SESSION;
    std::lock_guard<std::mutex> lock(s->mu);

    if (s->prompt_lu >= s->prompt.size()) {
        s->generating = true;
        return 0;
    }

    const int reste = static_cast<int>(s->prompt.size() - s->prompt_lu);
    const int n = std::min(kLotPrompt, reste);

    llama_batch batch = llama_batch_get_one(s->prompt.data() + s->prompt_lu, n);
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("llama_decode a echoue apres %zu tokens de prompt", s->prompt_lu);
        return ERR_DECODE;
    }
    s->prompt_lu += n;
    s->n_past    += n;

    if (s->prompt_lu >= s->prompt.size()) s->generating = true;
    return n;
}

/**
 * Produit le morceau de texte suivant. Retourne null quand la generation est
 * terminee (jeton de fin, budget epuise ou contexte plein). Peut retourner une
 * chaine vide si le token courant ne complete pas encore une sequence UTF-8.
 */
JNI_FN(jstring)
Java_com_jobmaker_llm_LlamaBridge_nativeNextPiece(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Session* s = as_session(handle);
    if (s == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(s->mu);
    if (!s->generating) return nullptr;

    if (s->n_emitted >= s->max_tokens || s->n_past + 1 >= s->n_ctx) {
        s->generating = false;
        return nullptr;
    }

    // llama_sampler_sample enregistre deja le token dans la chaine
    // d'echantillonnage (penalites incluses) : ne pas rappeler accept.
    const llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);

    if (llama_vocab_is_eog(s->vocab, tok)) {
        s->generating = false;
        return nullptr;
    }

    s->utf8_tail += piece_of(s->vocab, tok);
    const size_t cut = utf8_complete_prefix(s->utf8_tail);
    std::string out = s->utf8_tail.substr(0, cut);
    s->utf8_tail.erase(0, cut);

    llama_token next = tok;
    llama_batch batch = llama_batch_get_one(&next, 1);
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("llama_decode a echoue pendant la generation");
        s->generating = false;
        return nullptr;
    }
    s->n_past++;
    s->n_emitted++;

    return env->NewStringUTF(out.c_str());
}

JNI_FN(void)
Java_com_jobmaker_llm_LlamaBridge_nativeEndGenerate(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Session* s = as_session(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    s->generating = false;
    s->utf8_tail.clear();
    s->prompt.clear();
    s->prompt_lu = 0;
    clear_kv(s);
}

JNI_FN(jstring)
Java_com_jobmaker_llm_LlamaBridge_nativeSystemInfo(JNIEnv* env, jobject /*thiz*/) {
    ensure_backend();
    std::string info = "llama.cpp " JOBMAKER_LLAMA_TAG " | ";
    info += llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}
