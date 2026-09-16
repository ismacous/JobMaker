package com.jobmaker.llm.local

import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.ConfigMoteur
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.ModelManager
import com.jobmaker.llm.MoteurPret
import com.jobmaker.llm.MoteurTexte
import kotlin.math.min

/**
 * Le moteur historique : un GGUF charge dans le processus.
 *
 * Le code de chargement vivait dans l'orchestrateur ; il est ici desormais, ce
 * qui permet a l'orchestrateur d'ignorer completement ou tourne le calcul.
 */
class MoteurLocal(
    private val runtime: LlmRuntime,
    private val modelManager: ModelManager,
    private val config: ConfigMoteur,
) : MoteurTexte {

    override val nomCourt: String = "Sur l'appareil"

    override val distant: Boolean = false

    override suspend fun preparer(role: AgentRole): MoteurPret {
        val installes = modelManager.installed.value
        if (installes.isEmpty()) {
            throw LlmException(
                "Aucun modele installe sur le telephone. Ouvrez Reglages > Moteur d'IA " +
                    "pour utiliser une API gratuite, ou Reglages > Modeles d'IA pour " +
                    "telecharger un modele (Wi-Fi conseille)."
            )
        }
        val demande = config.modeleParRole[role]?.takeIf { it.isNotBlank() }
        val choisi = installes.firstOrNull { it.id == demande } ?: installes.first()
        val entree = modelManager.catalog.byId(choisi.id)

        val contexte = min(
            config.tailleContexte,
            entree?.contextMax ?: config.tailleContexte,
        ).coerceAtLeast(2048)

        val charge = runtime.ensureLoaded(
            modelId = choisi.id,
            filePath = choisi.file.absolutePath,
            contextSize = contexte,
            threads = config.threads,
            gpuLayers = config.couchesGpu,
            chargerEnMemoire = config.chargerEnMemoire,
        )
        return MoteurPret(
            nomModele = choisi.displayName,
            tailleContexte = charge.contextSize,
            brideRaisonnement = entree?.emitsReasoning ?: false,
        )
    }

    override suspend fun tokenCount(texte: String): Int = runtime.tokenCount(texte)

    override suspend fun completer(
        messages: List<ChatMessage>,
        params: GenerationParams,
        onLecturePrompt: ((Int, Int, Long) -> Unit)?,
        onToken: ((String) -> Unit)?,
    ): String = runtime.complete(messages, params, onLecturePrompt, onToken)

    override suspend fun liberer() = runtime.unload()
}
