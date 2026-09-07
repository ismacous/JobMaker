package com.jobmaker.agents

import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.JobAnalysis
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Profile
import java.text.Normalizer

/**
 * Controles deterministes, executes sans IA.
 *
 * Le relecteur IA rate des choses ; ces verifications-la, elles, ne ratent
 * jamais, parce qu'elles ne font que comparer des chaines. Elles attrapent
 * precisement le type d'erreur le plus grave : un employeur, un diplome ou un
 * chiffre apparu dans le CV sans exister dans le profil.
 */
object FactCheck {

    data class Rapport(
        /** Employeurs et etablissements du CV absents du profil. */
        val organisationsSuspectes: List<String>,
        /** Chiffres presents dans le CV et introuvables dans le profil. */
        val chiffresSuspects: List<String>,
        /** Mots-cles de l'annonce effectivement presents dans le CV. */
        val motsClesCouverts: List<String>,
        val motsClesAbsents: List<String>,
        val puceTropLongues: Int,
        val puceSansVerbe: Int,
        val premierePersonneDansCv: Boolean,
        val motsLettre: Int,
    ) {
        /** Couverture des mots-cles, de 0 a 100. */
        val scoreAts: Int
            get() {
                val total = motsClesCouverts.size + motsClesAbsents.size
                return if (total == 0) 100 else (motsClesCouverts.size * 100) / total
            }

        val aDesAlertes: Boolean
            get() = organisationsSuspectes.isNotEmpty() || chiffresSuspects.isNotEmpty()
    }

    fun verifier(profile: Profile, analyse: JobAnalysis, cv: CvContent, lettre: LetterContent): Rapport {
        val profilNormalise = normaliser(referenceProfil(profile))
        val cvTexte = cv.texteIntegral()
        val cvNormalise = normaliser(cvTexte)
        val lettreTexte = lettre.texteIntegral()

        // --- organisations ---
        // La comparaison se fait contre la liste des organisations du profil, et
        // non contre tout son texte : "Amazon Logistique" partagerait sinon le mot
        // "logistique" avec un "Bac pro logistique" et passerait pour connu.
        val orgsProfil = organisationsDuProfil(profile)
            .map { normaliser(it) }
            .filter { it.length >= 3 }
        val orgsCv = (cv.experiences.map { it.entreprise } + cv.formations.map { it.etablissement })
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
        val orgsSuspectes = orgsCv.filterNot { org ->
            val n = normaliser(org)
            when {
                n.isBlank() -> true
                // Nom identique, ou l'un contenu dans l'autre.
                orgsProfil.any { it.contains(n) || n.contains(it) } -> true
                // Nom reformule : "Carrefour Market" pour un profil qui dit "Carrefour".
                orgsProfil.any { jetonsCommuns(n, it) } -> true
                // Organisation citee ailleurs dans le profil (presentation, projets).
                profilNormalise.contains(n) -> true
                else -> false
            }
        }

        // --- chiffres ---
        // On ignore les annees (1900-2099) et les nombres a un chiffre :
        // trop de faux positifs pour trop peu de risque.
        val nombresProfil = extraireNombres(referenceProfil(profile)).toSet()
        val chiffresSuspects = extraireNombres(cvTexte)
            .filter { it !in nombresProfil }
            .distinct()

        // --- mots-cles ---
        val motsCles = (analyse.motsClesAts + analyse.competencesRequises + analyse.outils)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { normaliser(it) }
        val couverts = motsCles.filter { cvNormalise.contains(normaliser(it)) }
        val absents = motsCles - couverts.toSet()

        // --- forme ---
        val puces = cv.experiences.flatMap { it.puces }
        val tropLongues = puces.count { it.length > 190 }
        val sansVerbe = puces.count { !commenceParVerbeApparent(it) }
        val premierePersonne = Regex("\\b(je|j'ai|mon|ma|mes|moi)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(cv.accroche + " " + puces.joinToString(" "))

        return Rapport(
            organisationsSuspectes = orgsSuspectes,
            chiffresSuspects = chiffresSuspects,
            motsClesCouverts = couverts,
            motsClesAbsents = absents,
            puceTropLongues = tropLongues,
            puceSansVerbe = sansVerbe,
            premierePersonneDansCv = premierePersonne,
            motsLettre = lettreTexte.split(Regex("\\s+")).count { it.isNotBlank() },
        )
    }

    /** Les seules organisations que le candidat a reellement declarees. */
    private fun organisationsDuProfil(profile: Profile): List<String> = buildList {
        (profile.experiences + profile.benevolat).forEach { add(it.entreprise) }
        profile.formations.forEach { add(it.etablissement) }
        profile.certifications.forEach { add(it.organisme) }
    }.map { it.trim() }.filter { it.isNotBlank() }

    /** Tout le texte du profil, servant de reference factuelle. */
    private fun referenceProfil(profile: Profile): String = buildString {
        appendLine(profile.presentation)
        appendLine(profile.objectifPro)
        with(profile.identite) { appendLine("$prenom $nom $titre $ville $pays $dateNaissance") }
        (profile.experiences + profile.benevolat).forEach { e ->
            appendLine("${e.poste} ${e.entreprise} ${e.secteur} ${e.lieu} ${e.typeContrat} ${e.periode} ${e.contexte}")
            e.missions.forEach { appendLine(it) }
            e.realisations.forEach { appendLine(it) }
            appendLine(e.outils.joinToString(" "))
        }
        profile.formations.forEach { f ->
            appendLine("${f.diplome} ${f.etablissement} ${f.lieu} ${f.periode} ${f.niveau} ${f.mention}")
            appendLine(f.matieres.joinToString(" "))
        }
        profile.competences.forEach { g ->
            appendLine(g.categorie)
            g.items.forEach { appendLine("${it.nom} ${it.niveau} ${it.anneesExperience}") }
        }
        profile.langues.forEach { appendLine("${it.nom} ${it.niveau} ${it.certification}") }
        profile.certifications.forEach { appendLine("${it.nom} ${it.organisme} ${it.annee}") }
        profile.projets.forEach { appendLine("${it.nom} ${it.description} ${it.role} ${it.outils.joinToString(" ")}") }
        appendLine(profile.permis.joinToString(" "))
        appendLine(profile.centresInteret.joinToString(" "))
    }

    /** Minuscules, sans accents, sans ponctuation, espaces normalises. */
    fun normaliser(texte: String): String =
        Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9+#/. ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Tolerance pour les noms d'entreprise reformules : on considere deux
     * organisations comme la meme si elles partagent un mot distinctif (les
     * formes juridiques et les mots passe-partout etant ecartes).
     */
    private fun jetonsCommuns(orgNormalisee: String, referenceNormalisee: String): Boolean {
        val jetons = orgNormalisee.split(' ')
            .filter { it.length >= 4 && it !in MOTS_VIDES }
        if (jetons.isEmpty()) return false
        return jetons.any { referenceNormalisee.contains(it) }
    }

    private fun extraireNombres(texte: String): List<String> =
        Regex("\\d[\\d\\s.,]*%?").findAll(texte)
            .map { it.value.replace(" ", "").trim('.', ',') }
            .filter { brut ->
                val nu = brut.removeSuffix("%")
                val valeur = nu.replace(",", ".").toDoubleOrNull()
                when {
                    nu.length <= 1 -> false                       // trop de faux positifs
                    valeur != null && valeur in 1900.0..2099.0 -> false  // annees
                    else -> true
                }
            }
            .toList()

    private fun commenceParVerbeApparent(puce: String): Boolean {
        val premier = puce.trim().split(Regex("[\\s,]"))
            .firstOrNull()?.lowercase()?.trimEnd('.') ?: return false
        if (premier in VERBES_COURANTS) return true
        // Heuristique de repli : infinitif ou participe passe francais.
        return premier.endsWith("er") || premier.endsWith("ir") || premier.endsWith("re") ||
            premier.endsWith("e") || premier.endsWith("is") || premier.endsWith("it") ||
            premier.endsWith("ge") || premier.endsWith("ise")
    }

    private val MOTS_VIDES = setOf(
        "sarl", "sas", "sasu", "eurl", "group", "groupe", "france", "international",
        "company", "societe", "entreprise", "agence", "cabinet", "centre", "service",
    )

    private val VERBES_COURANTS = setOf(
        "gere", "gerer", "gestion", "encadre", "encadrer", "assure", "assurer",
        "realise", "realiser", "mis", "mise", "cree", "creer", "developpe", "developper",
        "optimise", "optimiser", "reduit", "reduire", "augmente", "augmenter",
        "forme", "former", "anime", "animer", "coordonne", "coordonner", "suivi",
        "controle", "controler", "livre", "livrer", "prepare", "preparer", "vendu",
        "vendre", "conseille", "conseiller", "accueilli", "accueillir", "negocie",
        "negocier", "planifie", "planifier", "maintenu", "maintenir", "installe",
        "installer", "diagnostique", "reparé", "reparer", "traite", "traiter",
        "participe", "participer", "contribue", "contribuer", "pilote", "piloter",
    )
}
