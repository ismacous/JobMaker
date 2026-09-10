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
import java.io.File
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

/** Pourquoi la generation s'est arretee. */
enum class RaisonArret(val libelle: String) {
    /** Le modele a emis son jeton de fin : cas normal. */
    FIN_DE_REPONSE("reponse terminee par le modele"),
    /** L'objet JSON attendu s'est referme : on a coupe la suite. */
    JSON_COMPLET("JSON referme, suite coupee"),
    /** Une sequence de fin de tour a ete ecrite en clair. */
    SEQUENCE_ARRET("marqueur de fin rencontre"),
    /** Budget de tokens epuise : la reponse est probablement tronquee. */
    BUDGET_EPUISE("budget de tokens epuise (reponse tronquee)"),
    /** Fenetre de contexte pleine. */
    CONTEXTE_PLEIN("fenetre de contexte pleine"),
    /** Generation interrompue par l'utilisateur. */
    ANNULE("interrompu"),
}

/**
 * Ce qu'a reellement coute un appel au modele.
 *
 * On ne diagnostique pas une lenteur en la devinant : chaque etape rend ses
 * deux phases chronometrees separement, avec la raison exacte de l'arret. Un
 * budget epuise a repetition ne se corrige pas comme un moteur lent.
 */
data class TraceAppel(
    val etape: String,
    val tokensPrompt: Int,
    val msPrompt: Long,
    val tokensEcrits: Int,
    val msEcriture: Long,
    val maxTokens: Int,
    val raison: RaisonArret,
    /** Tokens du prompt repris du cache au lieu d'etre recalcules. */
    val tokensReutilises: Int = 0,
    /** Temps passe a relire et reecrire le fichier de cache. */
    val msCache: Long = 0,
    /** Ce que le noyau dit avoir fait pendant cet appel. */
    val compteurs: CompteursSysteme = CompteursSysteme(0, 0, 0, 0),
) {
    val lectureParSeconde: Double
        get() = tokensCalcules * 1000.0 / msPrompt.coerceAtLeast(1)
    val ecritureParSeconde: Double
        get() = tokensEcrits * 1000.0 / msEcriture.coerceAtLeast(1)
    val totalMs: Long get() = msPrompt + msEcriture

    /** Tokens du prompt qu'il a vraiment fallu calculer. */
    val tokensCalcules: Int get() = tokensPrompt - tokensReutilises

    /**
     * Nombre moyen de coeurs reellement occupes pendant l'appel.
     *
     * C'est la mesure qui separe les deux lenteurs possibles. Proche du nombre
     * de threads : le telephone calcule, il est simplement a sa vitesse. Bien
     * en dessous : il attend -- la memoire, ou le stockage.
     */
    val coeursOccupes: Double
        get() = compteurs.msProcesseur.toDouble() / totalMs.coerceAtLeast(1)

    fun ligne(): String = ("%-22s lecture %5d tok en %6s (%5.1f tok/s) | " +
        "ecriture %5d/%d tok en %6s (%5.1f tok/s) | %s").format(
        etape.take(22), tokensCalcules, duree(msPrompt), lectureParSeconde,
        tokensEcrits, maxTokens, duree(msEcriture), ecritureParSeconde, raison.libelle,
    )

    /** Ce que le cache a evite. Vide quand il n'a rien servi. */
    fun ligneCache(): String = if (tokensReutilises <= 0) "" else
        "%-22s cache de prompt : %5d tokens repris sur %d, relus en %s".format(
            "", tokensReutilises, tokensPrompt, duree(msCache),
        )

    /**
     * Seconde ligne : ce que faisait la machine. Les defauts majeurs comptent
     * les pages qu'il a fallu aller relire sur le stockage -- un modele mappe
     * puis evince par Android se voit la, et nulle part ailleurs.
     */
    fun ligneMachine(): String = ("%-22s coeurs occupes %5.2f | defauts majeurs %7d | " +
        "lu sur stockage %s").format(
        "", coeursOccupes, compteurs.defautsMajeurs,
        if (compteurs.octetsLus < 0) "non mesurable"
        else "%.0f Mo".format(compteurs.octetsLus / 1e6),
    )

    private fun duree(ms: Long): String =
        if (ms < 10_000) "${ms} ms" else "%.1f s".format(ms / 1000.0)
}

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
                filePath, contextSize, threadsEcriture(threads), threads, gpuLayers,
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
            LlamaBridge.nativeSetThreads(h, nThreads, nThreads)
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

    /** Nombre de tokens que [messages] occupera une fois le gabarit applique. */
    suspend fun compterPrompt(messages: List<ChatMessage>): Int = mutex.withLock {
        val h = handle
        if (h == 0L) return@withLock estimateTokens(messages.joinToString("\n") { it.content })
        withContext(dispatcher) {
            max(0, LlamaBridge.nativeTokenCount(h, renderPrompt(h, messages)))
        }
    }

    /**
     * Ramene le budget d'ecriture a ce que la fenetre de contexte laisse
     * reellement libre une fois le prompt lu.
     *
     * Le moteur refuse de demarrer si prompt + budget depasse le contexte : ce
     * n'est pas une limite souple. Un budget genereux "au cas ou" ne coute donc
     * pas seulement du temps quand le modele s'en sert, il vole de la place au
     * prompt et fait echouer l'etape avant meme le premier token.
     *
     * @return les parametres ajustes, ou null si la place restante est trop
     *   faible pour esperer une reponse complete.
     */
    suspend fun ajusterBudget(
        messages: List<ChatMessage>,
        params: GenerationParams,
        minimum: Int = 320,
    ): Pair<GenerationParams, Int>? {
        val contexte = loaded?.contextSize ?: return params to 0
        val tokensPrompt = compterPrompt(messages)
        val libre = contexte - tokensPrompt - MARGE_CONTEXTE
        if (libre < minimum) return null
        return params.copy(maxTokens = min(params.maxTokens, libre)) to tokensPrompt
    }

    /**
     * Genere une reponse complete.
     *
     * @param onLecturePrompt appele a chaque lot de prompt lu, avec le nombre
     *   de tokens lus, le total et la duree ecoulee. C'est la phase pendant
     *   laquelle rien ne s'ecrit : sans cette mesure, impossible de distinguer
     *   un moteur lent d'un pipeline bloque.
     * @param onToken recoit le texte produit et le nombre de tokens qu'il a
     *   demande. Les appels sont regroupes : voir [MS_ENTRE_ENVOIS].
     * @param onTrace recoit le bilan chiffre de l'appel.
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        params: GenerationParams,
        etape: String = "",
        cacheDePrompt: File? = null,
        onLecturePrompt: ((lus: Int, total: Int, dureeMs: Long) -> Unit)? = null,
        onToken: ((texte: String, tokens: Int) -> Unit)? = null,
        onTrace: ((TraceAppel) -> Unit)? = null,
    ): String = mutex.withLock {
        val h = handle
        if (h == 0L) throw LlmException("Aucun modele charge.")

        withContext(dispatcher) {
            val prompt = renderPrompt(h, messages)
            val seed = if (params.seed >= 0) params.seed else (System.nanoTime() and 0x7FFFFFFF).toInt()

            val compteursAvant = CompteursSysteme.lire()
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
            val totalPrompt = rc

            // Ce qui a deja ete calcule une fois n'a pas a l'etre deux fois.
            // Le prefixe commun avec le cache est retrouve par comparaison des
            // tokens : rien a declarer, rien a invalider a la main.
            var msCache = 0L
            var reutilises = 0
            if (cacheDePrompt != null && cacheDePrompt.isFile) {
                val debut = System.currentTimeMillis()
                reutilises = runCatching {
                    LlamaBridge.nativeReutiliserCache(h, cacheDePrompt.absolutePath)
                }.getOrElse {
                    Log.w(TAG, "Cache de prompt illisible, on recalcule", it)
                    0
                }
                msCache += System.currentTimeMillis() - debut
            }

            // Lecture du prompt lot par lot : l'avancement remonte a l'interface
            // et l'annulation devient possible pendant cette phase.
            var lus = reutilises
            onLecturePrompt?.invoke(lus, totalPrompt, 0L)
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
            val dureeLecture = System.currentTimeMillis() - debutLecture - msCache

            // Le cache est reecrit quand il n'a pas beaucoup servi : premiere
            // generation, profil modifie, consignes changees. Tant qu'il couvre
            // l'essentiel du prompt, on ne reecrit pas un demi-gigaoctet pour
            // gagner quelques tokens.
            if (cacheDePrompt != null && reutilises < totalPrompt * SEUIL_REECRITURE_CACHE) {
                val debut = System.currentTimeMillis()
                runCatching {
                    cacheDePrompt.parentFile?.mkdirs()
                    if (!LlamaBridge.nativeSauverCache(h, cacheDePrompt.absolutePath)) {
                        cacheDePrompt.delete()
                    }
                }.onFailure { Log.w(TAG, "Cache de prompt non ecrit", it) }
                msCache += System.currentTimeMillis() - debut
            }

            val sb = StringBuilder()
            val detecteur = if (params.arretJsonComplet) DetecteurJsonComplet() else null
            var tokens = 0
            var raison = RaisonArret.FIN_DE_REPONSE

            // Les morceaux ne sont pas renvoyes un par un a l'interface. Chaque
            // envoi declenche une mise a jour d'etat, une recomposition et une
            // notification systeme ; a dix tokens par seconde cela occupe le fil
            // principal en permanence, et les threads de calcul, qui s'attendent
            // les uns les autres a chaque couche, paient chaque preemption.
            val enAttente = StringBuilder()
            var tokensEnAttente = 0
            var dernierEnvoi = System.currentTimeMillis()

            val debutRedaction = System.currentTimeMillis()
            try {
                while (true) {
                    if (!currentCoroutineContext().isActive) {
                        raison = RaisonArret.ANNULE
                        break
                    }
                    val piece = LlamaBridge.nativeNextPiece(h)
                    if (piece == null) {
                        raison = when {
                            tokens >= params.maxTokens -> RaisonArret.BUDGET_EPUISE
                            totalPrompt + tokens + 8 >= (loaded?.contextSize ?: Int.MAX_VALUE) ->
                                RaisonArret.CONTEXTE_PLEIN
                            else -> RaisonArret.FIN_DE_REPONSE
                        }
                        break
                    }
                    tokens++
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        enAttente.append(piece)
                        tokensEnAttente++
                        val maintenant = System.currentTimeMillis()
                        if (maintenant - dernierEnvoi >= MS_ENTRE_ENVOIS) {
                            onToken?.invoke(enAttente.toString(), tokensEnAttente)
                            enAttente.setLength(0)
                            tokensEnAttente = 0
                            dernierEnvoi = maintenant
                        }
                        if (hitStopSequence(sb, params.stopSequences)) {
                            raison = RaisonArret.SEQUENCE_ARRET
                            break
                        }
                        if (detecteur != null && detecteur.avaler(piece)) {
                            raison = RaisonArret.JSON_COMPLET
                            break
                        }
                    }
                }
            } finally {
                LlamaBridge.nativeEndGenerate(h)
            }
            if (tokensEnAttente > 0) onToken?.invoke(enAttente.toString(), tokensEnAttente)

            val dureeRedaction = System.currentTimeMillis() - debutRedaction
            val trace = TraceAppel(
                etape = etape,
                tokensPrompt = totalPrompt,
                msPrompt = dureeLecture,
                tokensEcrits = tokens,
                msEcriture = dureeRedaction,
                maxTokens = params.maxTokens,
                raison = raison,
                tokensReutilises = reutilises,
                msCache = msCache,
                compteurs = CompteursSysteme.lire() - compteursAvant,
            )
            Log.i(TAG, trace.ligne())
            trace.ligneCache().takeIf { it.isNotBlank() }?.let { Log.i(TAG, it) }
            Log.i(TAG, trace.ligneMachine())
            onTrace?.invoke(trace)
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
         * Place laissee libre dans la fenetre de contexte, au-dela du prompt et
         * du budget d'ecriture. Le moteur en reclame quelques-uns ; le reste
         * absorbe l'ecart entre le prompt compte ici et celui reellement rendu.
         */
        const val MARGE_CONTEXTE = 48

        /**
         * Intervalle minimal entre deux remontees de texte a l'interface.
         * Huit rafraichissements par seconde suffisent a voir que ca ecrit.
         */
        private const val MS_ENTRE_ENVOIS = 120L

        /**
         * En deca de cette part du prompt reprise du cache, on le reecrit.
         *
         * Le fichier pese environ 144 Ko par token -- un demi-gigaoctet pour un
         * prompt courant. Le reecrire quand il couvre deja les trois quarts du
         * prompt userait le stockage pour rien.
         */
        private const val SEUIL_REECRITURE_CACHE = 0.6

        /**
         * Sur les SoC recents (8 Elite : 2 Oryon prime + 6 performance), aller
         * au-dela de 6 threads fait plus de contention que de travail utile, et
         * laisse au moins un coeur libre pour l'interface.
         */
        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return max(2, min(6, cores - 2))
        }

        /**
         * Threads pour l'ecriture des tokens, deduits de ceux de la lecture.
         *
         * Ecrire un token oblige a relire tout le modele en memoire : le debit
         * plafonne sur la bande passante, pas sur le calcul. Mesure sur un
         * Snapdragon 8 Elite avec Qwen3 4B Q4 : 12,03 tok/s a quatre threads,
         * 12,01 a six. Les deux threads supplementaires ne produisent rien --
         * ils chauffent le telephone, qui se bride ensuite, et la vitesse
         * s'effondre au fil des minutes. La lecture du prompt, elle, calcule
         * vraiment et garde tous les threads.
         */
        fun threadsEcriture(threadsLecture: Int): Int = max(2, threadsLecture - 2)
    }
}
