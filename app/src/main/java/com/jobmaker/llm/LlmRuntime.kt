package com.jobmaker.llm

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class LlmException(message: String) : Exception(message)

/** Ce que l'application sait d'un modele installe et pret a servir. */
data class LoadedModelInfo(
    val modelId: String,
    val filePath: String,
    val contextSize: Int,
    val description: String,
    /** Vrai si les poids ont ete copies en memoire au lieu d'etre mappes. */
    val enMemoire: Boolean = false,
)

/**
 * Detient l'unique contexte llama.cpp vivant du processus.
 *
 * Un seul modele reside en memoire a la fois : meme sur un S25 Ultra, deux
 * modeles 4B quantifies charges en parallele font tuer l'application par le
 * gestionnaire de memoire Android. Quand une etape du pipeline demande un autre
 * modele, on decharge l'actuel puis on charge le nouveau -- le cout est de
 * quelques secondes grace au mmap, ce qui reste acceptable et permet malgre
 * tout d'affecter un modele different a chaque agent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loaded: LoadedModelInfo? = null

    val currentModel: LoadedModelInfo? get() = loaded

    val nativeAvailable: Boolean get() = LlamaBridge.available

    suspend fun systemInfo(): String = withContext(dispatcher) {
        if (!LlamaBridge.available) "Moteur natif indisponible"
        else LlamaBridge.nativeSystemInfo() ?: "inconnu"
    }

    /**
     * Charge [modelId] depuis [filePath] s'il n'est pas deja le modele resident.
     * Retourne les infos du modele desormais charge.
     */
    suspend fun ensureLoaded(
        modelId: String,
        filePath: String,
        contextSize: Int,
        threads: Int = defaultThreads(),
        gpuLayers: Int = 0,
        chargerEnMemoire: Boolean = false,
    ): LoadedModelInfo = mutex.withLock {
        val existing = loaded
        if (existing != null && existing.modelId == modelId &&
            existing.contextSize == contextSize && existing.enMemoire == chargerEnMemoire
        ) {
            return@withLock existing
        }
        withContext(dispatcher) {
            freeLocked()
            if (!LlamaBridge.available) {
                throw LlmException(
                    "Le moteur d'inference natif n'est pas embarque dans cette version " +
                        "de l'application (compilation sans NDK)."
                )
            }
            val h = LlamaBridge.nativeLoad(
                filePath, contextSize, threads, gpuLayers,
                /* useMmap = */ !chargerEnMemoire,
            )
            if (h == 0L) {
                throw LlmException(
                    "Impossible de charger le modele. Fichier corrompu, format GGUF " +
                        "non supporte, ou memoire insuffisante pour un contexte de $contextSize."
                )
            }
            handle = h
            val info = LoadedModelInfo(
                modelId = modelId,
                filePath = filePath,
                contextSize = LlamaBridge.nativeContextSize(h),
                description = LlamaBridge.nativeDescribe(h) ?: modelId,
                enMemoire = chargerEnMemoire,
            )
            loaded = info
            Log.i(TAG, "Modele resident : ${info.description}")
            info
        }
    }

    /**
     * Resultat d'une mesure : les deux phases chronometrees separement, avec ce
     * que le noyau dit avoir fait pendant ce temps.
     */
    data class Mesure(
        val nThreads: Int,
        /** Taille des lots de lecture du prompt. */
        val nLot: Int,
        val msLecture: Long,
        val msEcriture: Long,
        val tokensLus: Int,
        val tokensEcrits: Int,
        val compteurs: CompteursSysteme,
    ) {
        val lectureParSeconde: Double
            get() = tokensLus * 1000.0 / msLecture.coerceAtLeast(1)
        val ecritureParSeconde: Double
            get() = tokensEcrits * 1000.0 / msEcriture.coerceAtLeast(1)

        /**
         * Nombre moyen de coeurs reellement occupes. Proche du nombre de
         * threads = le telephone calcule. Bien en dessous de 1 = il attend.
         */
        val coeursOccupes: Double
            get() = compteurs.msProcesseur.toDouble() /
                (msLecture + msEcriture).coerceAtLeast(1)
    }

    /**
     * Mesure la lecture d'un prompt de [nPrompt] tokens, par lots de [nLot],
     * puis l'ecriture de [nGen] tokens, avec [nThreads] threads. Le modele
     * doit deja etre charge.
     */
    suspend fun mesurer(
        nPrompt: Int,
        nGen: Int,
        nThreads: Int,
        nLot: Int = nPrompt,
    ): Mesure = mutex.withLock {
        val h = handle
        if (h == 0L) throw LlmException("Aucun modele charge.")
        withContext(dispatcher) {
            LlamaBridge.nativeSetThreads(h, nThreads)
            val avant = CompteursSysteme.lire()
            val r = LlamaBridge.nativeBench(h, nPrompt, nGen, nLot)
            val apres = CompteursSysteme.lire()
            if (r.size < 4) throw LlmException("La mesure a echoue (decode impossible).")
            Mesure(
                nThreads = nThreads,
                nLot = nLot,
                msLecture = r[0],
                msEcriture = r[1],
                tokensLus = r[2].toInt(),
                tokensEcrits = r[3].toInt(),
                compteurs = apres - avant,
            )
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) { freeLocked() }
    }

    private fun freeLocked() {
        if (handle != 0L) {
            LlamaBridge.nativeFree(handle)
            handle = 0L
        }
        loaded = null
    }

    suspend fun tokenCount(text: String): Int = mutex.withLock {
        val h = handle
        if (h == 0L) return@withLock estimateTokens(text)
        withContext(dispatcher) { max(0, LlamaBridge.nativeTokenCount(h, text)) }
    }

    /** Estimation grossiere utilisee quand aucun modele n'est charge. */
    fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 1

    /**
     * Genere une reponse complete. [onToken] recoit les morceaux au fil de
     * l'eau, ce qui sert a la fois a l'affichage en direct et a montrer que
     * l'application n'est pas figee pendant les longues generations.
     */
    /**
     * Genere une reponse complete.
     *
     * @param onLecturePrompt appele a chaque lot de prompt lu, avec le nombre
     *   de tokens lus, le total et la duree ecoulee. C'est la phase pendant
     *   laquelle rien ne s'ecrit : sans cette mesure, impossible de distinguer
     *   un moteur lent d'un pipeline bloque.
     * @param onToken recoit les morceaux au fil de l'eau.
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        params: GenerationParams,
        onLecturePrompt: ((lus: Int, total: Int, dureeMs: Long) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null,
    ): String = mutex.withLock {
        val h = handle
        if (h == 0L) throw LlmException("Aucun modele charge.")

        withContext(dispatcher) {
            val prompt = renderPrompt(h, messages)
            val seed = if (params.seed >= 0) params.seed else (System.nanoTime() and 0x7FFFFFFF).toInt()

            val debutLecture = System.currentTimeMillis()
            val rc = LlamaBridge.nativeBeginGenerate(
                h, prompt, params.maxTokens, params.temperature, params.topP,
                params.topK, params.repeatPenalty, params.repeatLastN, seed,
            )
            if (rc < 0) {
                throw when (rc) {
                    LlamaBridge.ERR_PROMPT_TOO_LONG -> LlmException(
                        "Texte trop long pour la fenetre de contexte du modele " +
                            "(${loaded?.contextSize} tokens). Reduisez l'offre collee, " +
                            "ou augmentez la taille de contexte dans les reglages."
                    )
                    LlamaBridge.ERR_TOKENIZE -> LlmException("Echec de la tokenisation du prompt.")
                    LlamaBridge.ERR_DECODE -> LlmException("Echec du calcul du prompt par le modele.")
                    LlamaBridge.ERR_NO_SESSION -> LlmException("Aucun modele charge.")
                    else -> LlmException("Erreur du moteur d'inference (code $rc).")
                }
            }
            // Lecture du prompt lot par lot : l'avancement remonte a l'interface
            // et l'annulation devient possible pendant cette phase.
            val totalPrompt = rc
            var lus = 0
            onLecturePrompt?.invoke(0, totalPrompt, 0L)
            while (true) {
                if (!currentCoroutineContext().isActive) {
                    LlamaBridge.nativeEndGenerate(h)
                    return@withContext ""
                }
                val n = LlamaBridge.nativeLirePromptSuivant(h)
                if (n < 0) {
                    LlamaBridge.nativeEndGenerate(h)
                    throw LlmException("Echec du calcul du prompt par le modele (code $n).")
                }
                if (n == 0) break
                lus += n
                onLecturePrompt?.invoke(lus, totalPrompt, System.currentTimeMillis() - debutLecture)
            }
            val dureeLecture = System.currentTimeMillis() - debutLecture
            Log.i(TAG, "Prompt : $lus tokens lus en $dureeLecture ms")

            val sb = StringBuilder()
            var tokens = 0
            val debutRedaction = System.currentTimeMillis()
            try {
                while (true) {
                    if (!currentCoroutineContext().isActive) break
                    val piece = LlamaBridge.nativeNextPiece(h) ?: break
                    tokens++
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        onToken?.invoke(piece)
                        if (hitStopSequence(sb, params.stopSequences)) break
                    }
                }
            } finally {
                LlamaBridge.nativeEndGenerate(h)
            }
            val dureeRedaction = System.currentTimeMillis() - debutRedaction
            Log.i(TAG, "Redaction : $tokens tokens en ${dureeRedaction} ms")
            trimStopSequences(sb.toString(), params.stopSequences)
        }
    }

    private fun hitStopSequence(sb: StringBuilder, stops: List<String>): Boolean {
        if (stops.isEmpty()) return false
        // On ne regarde que la fin du tampon : suffisant et O(1) par token.
        val tailLen = min(sb.length, 64)
        val tail = sb.substring(sb.length - tailLen)
        return stops.any { it.isNotEmpty() && tail.contains(it) }
    }

    private fun trimStopSequences(text: String, stops: List<String>): String {
        var out = text
        for (s in stops) {
            if (s.isEmpty()) continue
            val idx = out.indexOf(s)
            if (idx >= 0) out = out.substring(0, idx)
        }
        return out.trim()
    }

    /**
     * Rend la conversation avec le gabarit declare par le GGUF. Si le modele
     * n'en fournit pas (ou si llama.cpp ne sait pas le rendre), on retombe sur
     * ChatML, que comprennent Qwen, la plupart des finetunes et, faute de mieux,
     * les modeles de base.
     */
    private fun renderPrompt(h: Long, messages: List<ChatMessage>): String {
        val roles = messages.map { it.role }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        val templated = runCatching {
            LlamaBridge.nativeApplyChatTemplate(h, roles, contents, true)
        }.getOrNull()
        if (!templated.isNullOrBlank()) return templated
        return chatMlFallback(messages)
    }

    private fun chatMlFallback(messages: List<ChatMessage>): String = buildString {
        for (m in messages) {
            append("<|im_start|>").append(m.role).append('\n')
            append(m.content).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    companion object {
        private const val TAG = "LlmRuntime"

        /**
         * Sur les SoC recents (8 Elite : 2 Oryon prime + 6 performance), aller
         * au-dela de 6 threads fait plus de contention que de travail utile, et
         * laisse au moins un coeur libre pour l'interface.
         */
        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return max(2, min(6, cores - 2))
        }
    }
}
