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

    // Tampon des candidats d'echantillonnage, alloue une seule fois.
    // llama_sampler_sample() en alloue un neuf a chaque token : sur un vocabulaire
    // de 150 000 entrees cela fait 1,8 Mo demandes puis rendus au systeme a chaque
    // mot ecrit, ce que l'allocateur d'Android sert par mmap/munmap. On garde donc
    // le notre et on appelle apply/accept nous-memes.
    std::vector<llama_token_data> candidats;

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
//
// C'est aussi le grain de l'annulation : la lecture ne peut etre interrompue
// qu'entre deux lots. A la centaine de tokens par seconde qu'un telephone
// recent atteint en lecture, un lot de 512 dure quelques secondes -- assez
// court pour que le bouton reponde, assez long pour que le calcul soit
// efficace.
constexpr int kLotPrompt = 512;

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

/**
 * Tire le token suivant a partir des logits du dernier decode, en reutilisant
 * le tampon de candidats de la session. Equivalent a llama_sampler_sample,
 * sans l'allocation d'un vecteur de la taille du vocabulaire a chaque appel.
 */
llama_token echantillonner(Session* s) {
    const float* logits = llama_get_logits_ith(s->ctx, -1);
    if (logits == nullptr) return -1;

    const int n_vocab = llama_vocab_n_tokens(s->vocab);
    if (n_vocab <= 0) return -1;

    s->candidats.resize(static_cast<size_t>(n_vocab));
    for (int i = 0; i < n_vocab; ++i) {
        s->candidats[i] = llama_token_data{i, logits[i], 0.0f};
    }

    llama_token_data_array arr = {
        /*.data     =*/ s->candidats.data(),
        /*.size     =*/ s->candidats.size(),
        /*.selected =*/ -1,
        /*.sorted   =*/ false,
    };

    llama_sampler_apply(s->sampler, &arr);
    if (arr.selected < 0 || static_cast<size_t>(arr.selected) >= arr.size) return -1;

    const llama_token tok = arr.data[arr.selected].id;
    llama_sampler_accept(s->sampler, tok);
    return tok;
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
    // 512 est la valeur par defaut de llama.cpp, et elle ne coute presque rien
    // ici : les tampons de calcul grandissent avec le nombre de tokens du lot
    // fois la largeur du modele, soit quelques megaoctets, et non avec le
    // vocabulaire -- les logits ne sont demandes que pour le dernier token.
    // Des lots plus grands amortissent mieux la construction du graphe et
    // donnent aux multiplications de matrices une forme plus favorable.
    cp.n_batch   = 512;
    cp.n_ubatch  = 512;
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

    const llama_token tok = echantillonner(s);
    if (tok < 0) {
        s->generating = false;
        return nullptr;
    }

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

// ---------------------------------------------------------------------------
// Mesure
//
// On ne peut pas diagnostiquer une lenteur en la devinant : ces deux fonctions
// existent pour mesurer sur l'appareil, separement, la lecture d'un prompt
// (calcul par lots, limite par la puissance de calcul) et l'ecriture de tokens
// (limite par la bande passante memoire). Cote Kotlin, on encadre l'appel par
// la lecture de /proc/self/stat : si le temps processeur consomme est tres
// inferieur au temps ecoule, le moteur attend la memoire ; s'il en est un
// multiple, il calcule vraiment.
// ---------------------------------------------------------------------------

JNI_FN(void)
Java_com_jobmaker_llm_LlamaBridge_nativeSetThreads(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong handle, jint n_threads) {
    Session* s = as_session(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    llama_set_n_threads(s->ctx, n_threads, n_threads);
}

/**
 * Lit un prompt synthetique de n_prompt tokens par lots de n_lot, puis ecrit
 * n_gen tokens.
 *
 * Retourne {ms de lecture, ms d'ecriture, tokens lus, tokens ecrits}, ou un
 * tableau vide en cas d'echec. N'utilise pas l'etat de generation courant :
 * le cache KV est vide avant et apres.
 *
 * La longueur du prompt compte autant que le reste : elle fixe la taille du
 * cache KV, donc le cout de l'attention, pour la lecture comme pour les
 * tokens ecrits ensuite.
 */
JNI_FN(jlongArray)
Java_com_jobmaker_llm_LlamaBridge_nativeBench(JNIEnv* env, jobject /*thiz*/,
                                              jlong handle, jint n_prompt, jint n_gen,
                                              jint n_lot) {
    Session* s = as_session(handle);
    if (s == nullptr) return env->NewLongArray(0);
    std::lock_guard<std::mutex> lock(s->mu);

    // Un texte quelconque pour obtenir des identifiants de tokens valides, que
    // l'on repete ensuite en boucle jusqu'a la longueur demandee.
    std::vector<llama_token> graine =
        tokenize(s->vocab, "Le candidat recherche un poste stable dans la logistique. ", false);
    if (graine.empty()) return env->NewLongArray(0);

    const int voulu = std::max(1, std::min<int>(n_prompt, s->n_ctx - n_gen - 8));
    std::vector<llama_token> tokens(voulu);
    for (int i = 0; i < voulu; ++i) tokens[i] = graine[i % graine.size()];

    clear_kv(s);
    build_sampler(s, /*temp=*/0.0f, 1.0f, 0, 1.0f, 64, 0);

    jlong out[4] = {0, 0, 0, 0};

    // Taille de lot imposee par l'appelant : c'est le point que le banc doit
    // pouvoir faire varier, la generation reelle lisant par lots de kLotPrompt
    // quand le banc n'en faisait qu'un seul.
    const int lot = std::max(1, std::min<int>(n_lot > 0 ? n_lot : kLotPrompt, kLotPrompt));

    const int64_t t0 = llama_time_us();
    int lus = 0;
    while (lus < voulu) {
        const int n = std::min(lot, voulu - lus);
        llama_batch batch = llama_batch_get_one(tokens.data() + lus, n);
        if (llama_decode(s->ctx, batch) != 0) { clear_kv(s); return env->NewLongArray(0); }
        lus += n;
    }
    const int64_t t1 = llama_time_us();

    int ecrits = 0;
    while (ecrits < n_gen) {
        llama_token tok = echantillonner(s);
        if (tok < 0) break;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) break;
        ++ecrits;
    }
    const int64_t t2 = llama_time_us();

    clear_kv(s);
    s->generating = false;

    out[0] = static_cast<jlong>((t1 - t0) / 1000);
    out[1] = static_cast<jlong>((t2 - t1) / 1000);
    out[2] = lus;
    out[3] = ecrits;

    jlongArray arr = env->NewLongArray(4);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 4, out);
    return arr;
}

JNI_FN(jstring)
Java_com_jobmaker_llm_LlamaBridge_nativeSystemInfo(JNIEnv* env, jobject /*thiz*/) {
    ensure_backend();
    std::string info = "llama.cpp " JOBMAKER_LLAMA_TAG " | ";
    info += llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}
