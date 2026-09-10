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
    /** Reglage effectif du moteur : fenetre, threads, mode de chargement. */
    val modeleDetail: String = "",
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
        if (modeleDetail.isNotBlank()) appendLine("Reglage : $modeleDetail")
        appendLine("Duree totale : %.1f s".format(dureeTotaleMs / 1000.0))
        appendLine()
        traces.forEach {
            appendLine(it.ligne())
            it.ligneCache().takeIf { l -> l.isNotBlank() }?.let { l -> appendLine(l) }
            appendLine(it.ligneMachine())
        }
        appendLine()

        val lus = traces.sumOf { it.tokensCalcules }
        val repris = traces.sumOf { it.tokensReutilises }
        val ecrits = traces.sumOf { it.tokensEcrits }
        val msLecture = traces.sumOf { it.msPrompt }
        val msEcriture = traces.sumOf { it.msEcriture }
        appendLine("Total lu    : $lus tokens en %.1f s (%.1f tok/s)".format(
            msLecture / 1000.0, lus * 1000.0 / msLecture.coerceAtLeast(1)))
        appendLine("Total ecrit : $ecrits tokens en %.1f s (%.1f tok/s)".format(
            msEcriture / 1000.0, ecrits * 1000.0 / msEcriture.coerceAtLeast(1)))
        if (repris > 0 && lus > 0) {
            val vitesse = lus * 1000.0 / msLecture.coerceAtLeast(1)
            appendLine("Repris du cache : $repris tokens jamais recalcules, " +
                "soit environ %.0f s economisees".format(repris / vitesse))
        } else if (repris > 0) {
            appendLine("Repris du cache : $repris tokens jamais recalcules")
        }

        val msCache = traces.sumOf { it.msCache }
        if (msCache > 200) {
            appendLine("Fichier de cache : %.1f s de lecture et d'ecriture disque"
                .format(msCache / 1000.0))
        }

        val hors = dureeTotaleMs - msLecture - msEcriture - msCache
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
        // Ces deux verdicts se contredisent, et c'est voulu : ils ne peuvent pas
        // etre vrais en meme temps. Des coeurs pleins avec une vitesse qui
        // s'effondre, c'est la chauffe. Des coeurs vides avec des defauts
        // majeurs, c'est le modele qu'Android evince et qu'il faut relire.
        val defauts = traces.sumOf { it.compteurs.defautsMajeurs }
        val occupation = traces.filter { it.totalMs > 1000 }
        val coeursMoyens = if (occupation.isEmpty()) 0.0
        else occupation.sumOf { it.compteurs.msProcesseur }.toDouble() /
            occupation.sumOf { it.totalMs }.coerceAtLeast(1)

        // Un defaut majeur coute une lecture sur stockage, soit environ 80 us
        // sur de l'UFS. Le seuil precedent -- mille defauts -- accusait la
        // memoire pour huit centiemes de seconde perdues, et faisait reduire un
        // contexte qui n'y etait pour rien. On compare donc au temps reel : sous
        // 5 % du total, l'eviction existe mais n'explique rien.
        val octetsRelus = traces.sumOf { it.compteurs.octetsLus.coerceAtLeast(0) }
        val msDefauts = defauts * MS_PAR_DEFAUT_MAJEUR
        if (defauts > 0) {
            appendLine()
            append("$defauts pages relues sur le stockage (%.0f Mo)".format(octetsRelus / 1e6))
            if (msDefauts > dureeTotaleMs * 0.05) {
                appendLine(" : environ %.0f s perdues a attendre le stockage. Android evince "
                    .format(msDefauts / 1000.0) +
                    "les poids du modele faute de memoire. Reduisez la fenetre de contexte, " +
                    "ou fermez les autres applications.")
            } else {
                appendLine(" : environ %.0f s, soit moins de 5 %% du total. L'eviction existe "
                    .format(msDefauts / 1000.0) + "mais n'explique pas la lenteur.")
            }
        }
        appendLine()
        appendLine(
            when {
                coeursMoyens >= 3.0 ->
                    "Coeurs occupes : %.2f en moyenne. Le processeur calcule a plein regime. "
                        .format(coeursMoyens) +
                        "Ce qui reste lent l'est parce que la machine va a cette vitesse-la, " +
                        "pas parce qu'elle attend."
                coeursMoyens >= 1.5 ->
                    "Coeurs occupes : %.2f en moyenne. Le processeur travaille a temps partiel : "
                        .format(coeursMoyens) +
                        "contention entre threads, ou attente de la memoire."
                else ->
                    "Coeurs occupes : %.2f en moyenne. Le processeur attend plus qu'il ne calcule."
                        .format(coeursMoyens)
            }
        )

        // Avec plusieurs etapes, l'ecart entre la premiere et la derniere mesure
        // le bridage thermique : meme machine, meme travail, vitesse divisee.
        val premiere = traces.firstOrNull { it.tokensEcrits > 20 }
        val derniere = traces.lastOrNull { it.tokensEcrits > 20 }
        if (premiere != null && derniere != null && premiere !== derniere &&
            derniere.ecritureParSeconde < premiere.ecritureParSeconde * 0.6
        ) {
            appendLine("La vitesse d'ecriture est tombee de %.1f a %.1f tok/s d'une etape a "
                .format(premiere.ecritureParSeconde, derniere.ecritureParSeconde) +
                "l'autre : le telephone chauffe et se bride. Retirez la coque, posez-le sur " +
                "une surface froide, et debranchez-le -- la charge ajoute de la chaleur sans " +
                "rien accelerer.")
        }
    }.trim()

    private companion object {
        /** Latence d'une lecture sur stockage UFS, pour chiffrer les defauts majeurs. */
        const val MS_PAR_DEFAUT_MAJEUR = 0.08
    }
}
