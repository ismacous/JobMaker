package com.jobmaker.work

/**
 * Ou en est le modele. La distinction compte : la lecture du prompt est une
 * phase pendant laquelle rien ne s'ecrit, et sans la nommer on croit
 * l'application figee.
 */
enum class PhaseGeneration(val libelle: String) {
    PREPARATION("Chargement du modele"),
    LECTURE("Lecture de l'annonce et du profil"),
    REDACTION("Redaction"),
}

data class EtatGeneration(
    val enCours: Boolean = false,
    val etapeIndex: Int = 0,
    val etapesTotal: Int = 2,
    val etapeTitre: String = "",
    val etapeDetail: String = "",
    val modeleActuel: String = "",
    /** Derniers tokens produits, pour montrer que ca avance. */
    val apercuBrut: String = "",
    val avertissements: List<String> = emptyList(),
    val erreur: String? = null,
    val candidatureId: String? = null,
    // --- mesures ---
    val phase: PhaseGeneration = PhaseGeneration.PREPARATION,
    /** Horodatage du lancement, 0 si rien n'a demarre. */
    val debutMs: Long = 0L,
    val promptLus: Int = 0,
    val promptTotal: Int = 0,
    val msLecturePrompt: Long = 0L,
    val tokensEcrits: Int = 0,
    val debutRedactionMs: Long = 0L,
) {
    val progression: Float
        get() = if (etapesTotal == 0) 0f else etapeIndex.toFloat() / etapesTotal

    /** Vitesse de lecture du prompt, en tokens par seconde. */
    val vitesseLecture: Double
        get() = if (msLecturePrompt <= 0) 0.0 else promptLus * 1000.0 / msLecturePrompt

    /** Vitesse de redaction, en tokens par seconde. */
    fun vitesseRedaction(maintenantMs: Long): Double {
        if (debutRedactionMs <= 0L || tokensEcrits <= 0) return 0.0
        val ecoule = maintenantMs - debutRedactionMs
        return if (ecoule <= 0) 0.0 else tokensEcrits * 1000.0 / ecoule
    }

    /** Avancement de la lecture du prompt, de 0 a 1. */
    val progressionLecture: Float
        get() = if (promptTotal <= 0) 0f else (promptLus.toFloat() / promptTotal).coerceIn(0f, 1f)
}
