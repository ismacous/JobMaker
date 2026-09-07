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

    // Codes d'erreur renvoyes par nativeBeginGenerate (voir llama_jni.cpp).
    const val OK = 0
    const val ERR_NO_SESSION = -1
    const val ERR_PROMPT_TOO_LONG = -2
    const val ERR_DECODE = -3
    const val ERR_TOKENIZE = -4

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

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

    /** null = generation terminee. Chaine vide = UTF-8 encore incomplet. */
    external fun nativeNextPiece(handle: Long): String?

    external fun nativeEndGenerate(handle: Long)

    external fun nativeSystemInfo(): String?
}
