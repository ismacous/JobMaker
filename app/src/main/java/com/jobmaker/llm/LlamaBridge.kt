package com.jobmaker.llm

import android.util.Log

/**
 * Declarations JNI vers app/src/main/cpp/llama_jni.cpp.
 *
 * Aucune de ces methodes n'est sure vis-a-vis des threads : un contexte
 * llama.cpp ne peut etre utilise que par un appelant a la fois. La
 * serialisation est faite dans [LlmRuntime], pas ici.
 */
internal object LlamaBridge {

    /** false si la bibliotheque native n'a pas pu etre chargee (build sans NDK). */
    val available: Boolean = try {
        System.loadLibrary("jobmaker_llm")
        true
    } catch (t: Throwable) {
        Log.e("LlamaBridge", "Bibliotheque native indisponible", t)
        false
    }

    // nativeBeginGenerate renvoie le nombre de tokens du prompt quand tout va
    // bien ; seules les valeurs negatives sont des erreurs (voir llama_jni.cpp).
    const val ERR_NO_SESSION = -1
    const val ERR_PROMPT_TOO_LONG = -2
    const val ERR_DECODE = -3
    const val ERR_TOKENIZE = -4

    /**
     * @param useMmap true : les poids restent des pages du fichier (ouverture
     *   rapide, mais Android peut les evincer). false : tout est copie en
     *   memoire (ouverture plus lente, debit ensuite constant).
     */
    external fun nativeLoad(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
    ): Long

    external fun nativeFree(handle: Long)

    external fun nativeContextSize(handle: Long): Int

    external fun nativeTokenCount(handle: Long, text: String): Int

    external fun nativeDescribe(handle: Long): String?

    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /** Retourne le nombre de tokens du prompt, ou un code d'erreur negatif. */
    external fun nativeBeginGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
    ): Int

    /**
     * Lit le lot suivant du prompt. Retourne le nombre de tokens lus, 0 quand
     * le prompt est entierement lu, ou un code d'erreur negatif.
     */
    external fun nativeLirePromptSuivant(handle: Long): Int

    /** null = generation terminee. Chaine vide = UTF-8 encore incomplet. */
    external fun nativeNextPiece(handle: Long): String?

    external fun nativeEndGenerate(handle: Long)

    /**
     * Change le nombre de threads sans recharger le modele.
     *
     * @param nThreads pour l'ecriture des tokens, limitee par la memoire.
     * @param nThreadsBatch pour la lecture du prompt, limitee par le calcul.
     */
    external fun nativeSetThreads(handle: Long, nThreads: Int, nThreadsBatch: Int)

    /**
     * Lit un prompt synthetique par lots de [nLot], puis ecrit des tokens, en
     * chronometrant les deux phases separement.
     *
     * @return {ms de lecture, ms d'ecriture, tokens lus, tokens ecrits}, ou un
     *   tableau vide en cas d'echec.
     */
    external fun nativeBench(handle: Long, nPrompt: Int, nGen: Int, nLot: Int): LongArray

    external fun nativeSystemInfo(): String?
}
