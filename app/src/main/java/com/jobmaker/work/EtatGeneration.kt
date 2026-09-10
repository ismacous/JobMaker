package com.jobmaker.work

import com.jobmaker.llm.RaisonArret
import com.jobmaker.llm.TraceAppel

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
    /** Bilan chiffre de chaque appel au modele, dans l'ordre. */
    val traces: List<TraceAppel> = emptyList(),
    /** Duree totale de la generation, renseignee a la fin. */
    val dureeTotaleMs: Long = 0L,
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

    /**
     * Compte-rendu copiable de la generation.
     *
     * Sans lui, une lenteur ne se discute qu'avec des impressions. Avec lui on
     * voit d'un coup d'oeil laquelle des deux phases coute, si le modele a
     * ecrit ce qu'il fallait ou s'il a rempli son budget, et si la vitesse
     * s'effondre entre la premiere etape et la derniere -- signe que le
     * telephone chauffe et se bride.
     */
    fun rapport(): String = buildString {
        if (traces.isEmpty()) return ""
        appendLine("BILAN DE LA GENERATION")
        if (modeleActuel.isNotBlank()) appendLine("Modele : $modeleActuel")
        appendLine("Duree totale : %.1f s".format(dureeTotaleMs / 1000.0))
        appendLine()
        traces.forEach { appendLine(it.ligne()) }
        appendLine()

        val lus = traces.sumOf { it.tokensPrompt }
        val ecrits = traces.sumOf { it.tokensEcrits }
        val msLecture = traces.sumOf { it.msPrompt }
        val msEcriture = traces.sumOf { it.msEcriture }
        appendLine("Total lu    : $lus tokens en %.1f s (%.1f tok/s)".format(
            msLecture / 1000.0, lus * 1000.0 / msLecture.coerceAtLeast(1)))
        appendLine("Total ecrit : $ecrits tokens en %.1f s (%.1f tok/s)".format(
            msEcriture / 1000.0, ecrits * 1000.0 / msEcriture.coerceAtLeast(1)))

        val hors = dureeTotaleMs - msLecture - msEcriture
        if (hors > 1000) {
            appendLine("Hors modele : %.1f s (chargement, verifications, ecriture disque)"
                .format(hors / 1000.0))
        }

        val budgets = traces.count { it.raison == RaisonArret.BUDGET_EPUISE }
        if (budgets > 0) {
            appendLine()
            appendLine("$budgets etape(s) ont epuise leur budget de tokens : le modele n'a " +
                "pas su s'arreter. C'est la premiere cause de lenteur a corriger.")
        }
        val premiere = traces.firstOrNull { it.tokensEcrits > 20 }
        val derniere = traces.lastOrNull { it.tokensEcrits > 20 }
        if (premiere != null && derniere != null && premiere !== derniere &&
            derniere.ecritureParSeconde < premiere.ecritureParSeconde * 0.6
        ) {
            appendLine()
            appendLine("La vitesse d'ecriture a chute de %.1f a %.1f tok/s entre la premiere et "
                .format(premiere.ecritureParSeconde, derniere.ecritureParSeconde) +
                "la derniere etape : le telephone chauffe et se bride, ou la memoire manque.")
        }
    }.trim()
}
