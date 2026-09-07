package com.jobmaker.llm

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Role du pipeline auquel un modele peut etre affecte. */
enum class AgentRole(val label: String, val description: String) {
    ANALYSIS("Analyse d'offre", "Lit l'annonce et en extrait les exigences reelles"),
    STRATEGY("Strategie", "Choisit quoi mettre en avant dans votre profil"),
    WRITING("Redaction", "Ecrit le CV et la lettre de motivation"),
    REVIEW("Relecture", "Traque les inventions, le hors-sujet et les oublis"),
    EXPLAIN("Explication de poste", "Vous explique un metier que vous ne connaissez pas"),
}

enum class ModelTier { RECOMMENDED, FAST, MAX_QUALITY, HEAVY }

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    /** Depot HuggingFace, ex. "unsloth/Qwen3-4B-Instruct-2507-GGUF". */
    val repo: String,
    /** Motif de quantification recherche dans les fichiers du depot. */
    val quant: String,
    /** Nom de fichier de repli si l'API HuggingFace est injoignable. */
    @SerialName("fallback_file") val fallbackFile: String,
    @SerialName("approx_size_mb") val approxSizeMb: Long,
    @SerialName("min_ram_gb") val minRamGb: Int,
    @SerialName("context_max") val contextMax: Int,
    @SerialName("default_context") val defaultContext: Int,
    val languages: String,
    val tier: ModelTier,
    /** Vrai pour les modeles qui emettent des blocs de raisonnement <think>. */
    @SerialName("emits_reasoning") val emitsReasoning: Boolean = false,
    @SerialName("good_for") val goodFor: List<AgentRole> = emptyList(),
    val notes: String = "",
) {
    val approxSizeLabel: String
        get() = if (approxSizeMb >= 1024) String.format("%.1f Go", approxSizeMb / 1024.0)
        else "$approxSizeMb Mo"

    val fileName: String get() = "$id.gguf"
}

@Serializable
data class ModelCatalog(
    val version: Int,
    val models: List<CatalogModel>,
) {
    fun byId(id: String): CatalogModel? = models.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): ModelCatalog {
            val text = context.assets.open("models_catalog.json")
                .bufferedReader().use { it.readText() }
            return json.decodeFromString(text)
        }
    }
}
