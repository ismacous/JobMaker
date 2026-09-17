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
        /**
         * Diplomes annonces dans le CV et absents du profil. La faute la plus
         * grave que l'application puisse commettre : l'annonce exige un diplome,
         * le modele le recopie, et le CV devient un faux.
         */
        val diplomesSuspects: List<String>,
        /** Mots-cles de l'annonce effectivement presents dans le CV. */
        val motsClesCouverts: List<String>,
        val motsClesAbsents: List<String>,
        val puceTropLongues: Int,
        val puceSansVerbe: Int,
        val premierePersonneDansCv: Boolean,
        val motsLettre: Int,
        /**
         * L'accroche dit ce que le candidat cherche au lieu de ce qu'il apporte.
         * C'est la premiere ligne lue d'un CV : elle doit se lire comme une offre,
         * pas comme une demande.
         */
        val accrocheParleDeRecherche: Boolean,
        /** Un paragraphe de la lettre resume l'annonce au lieu de s'adresser a elle. */
        val lettreResumeLAnnonce: Boolean,
        /** Un paragraphe annonce ce qui sera fait "dans les premiers mois". */
        val lettreProjetteLesPremiersMois: Boolean,
    ) {
        /** Couverture des mots-cles, de 0 a 100. */
        val scoreAts: Int
            get() {
                val total = motsClesCouverts.size + motsClesAbsents.size
                return if (total == 0) 100 else (motsClesCouverts.size * 100) / total
            }

        /**
         * Ce qui justifie de relancer une passe de correction. Les trois marques
         * de machine y figurent : elles ne sont pas des mensonges, mais elles
         * font jeter la candidature aussi surement.
         */
        val aDesAlertes: Boolean
            get() = organisationsSuspectes.isNotEmpty() || chiffresSuspects.isNotEmpty() ||
                diplomesSuspects.isNotEmpty() || accrocheParleDeRecherche ||
                lettreResumeLAnnonce || lettreProjetteLesPremiersMois
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

        // --- diplomes ---
        // Compares aux seuls diplomes declares, pas au texte entier du profil :
        // un "Assistant de Service Social" invente se retrouverait sinon
        // "connu" parce que le profil contient les mots "assistant" et "social".
        val diplomesProfil = profile.formations.map { normaliser(it.diplome) }
            .filter { it.length >= 3 }
        val diplomesSuspects = cv.formations.map { it.diplome.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .filterNot { d ->
                val n = normaliser(d)
                diplomesProfil.any { p ->
                    // Un mot en commun ne suffit pas pour un diplome : "licence
                    // professionnelle logistique" partagerait "logistique" avec
                    // un "bac pro logistique" et passerait pour le meme titre.
                    // Le niveau doit concorder aussi.
                    val memeIntitule = p.contains(n) || n.contains(p) || jetonsCommuns(n, p)
                    memeIntitule && niveauCompatible(n, p)
                }
            }

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

        // --- les trois marques de machine ---
        // Les consignes les interdisent ; ceci verifie qu'elles ont ete suivies.
        // Un modele obeit la plupart du temps, pas toujours, et ces trois defauts
        // se voient a la premiere ligne par quelqu'un qui lit des candidatures
        // toute la journee.
        val accrocheNormalisee = normaliser(cv.accroche)
        val accrocheCherche = MOTIFS_RECHERCHE.any { it.containsMatchIn(accrocheNormalisee) }

        val premierParagraphe = normaliser(lettre.paragraphes.firstOrNull().orEmpty())
        // Un paragraphe qui decrit le poste ne parle jamais du candidat : pas de
        // premiere personne, et un verbe d'exigence. Les deux ensemble suffisent.
        val resumeAnnonce = premierParagraphe.isNotBlank() &&
            !Regex("\\b(je|j |mon|ma|mes|moi|nous)\\b").containsMatchIn(premierParagraphe) &&
            MOTIFS_DESCRIPTION.any { it.containsMatchIn(premierParagraphe) }

        // La conclusion a le droit au futur -- "je reste disponible", "je pourrai
        // vous rencontrer" -- donc on ne l'inspecte pas. Ce sont les paragraphes
        // du corps qui ne doivent rien promettre.
        val corps = lettre.paragraphes.dropLast(1)
        val corpsNormalise = normaliser(corps.joinToString(" "))
        val projection = MOTIFS_PROJECTION.any { it.containsMatchIn(corpsNormalise) } ||
            MOTIF_PLAN_NUMEROTE.containsMatchIn(corps.joinToString(" "))

        return Rapport(
            organisationsSuspectes = orgsSuspectes,
            chiffresSuspects = chiffresSuspects,
            diplomesSuspects = diplomesSuspects,
            motsClesCouverts = couverts,
            motsClesAbsents = absents,
            puceTropLongues = tropLongues,
            puceSansVerbe = sansVerbe,
            premierePersonneDansCv = premierePersonne,
            motsLettre = lettreTexte.split(Regex("\\s+")).count { it.isNotBlank() },
            accrocheParleDeRecherche = accrocheCherche,
            lettreResumeLAnnonce = resumeAnnonce,
            lettreProjetteLesPremiersMois = projection,
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

    /**
     * Une accroche qui annonce ce que le candidat veut obtenir. Les motifs sont
     * volontairement etroits : "recherche" seul apparait legitimement ailleurs
     * ("recherche de solutions"), c'est la tournure complete qui trahit.
     */
    private val MOTIFS_RECHERCHE = listOf(
        Regex("\\b(recherche|cherche) (un|une|des|actuellement|a )"),
        Regex("\\ba la recherche d"),
        Regex("\\ben recherche d"),
        Regex("\\bsouhaite (integrer|rejoindre|obtenir|evoluer|me reconvertir)"),
        Regex("\\bdisponible pour un (cdd|cdi|contrat|poste)"),
    )

    /** Verbes par lesquels on decrit un poste a celui qui l'a redige. */
    private val MOTIFS_DESCRIPTION = listOf(
        Regex("\\b(requiert|necessite|exige|implique|suppose|demande de)\\b"),
        Regex("\\b(consiste a|repose sur|s articule autour)\\b"),
    )

    /**
     * La lettre qui parle au futur du travail.
     *
     * Ce n'est pas qu'une question de style : un plan annonce se lit comme un
     * engagement. Le recruteur attend ce qui y figure des le premier jour, et le
     * candidat doit tenir un programme ecrit sans connaitre la maison.
     *
     * La famille entiere est donc visee, pas seulement "les premiers mois" : le
     * modele qui contourne une formule interdite en trouve une autre.
     */
    private val MOTIFS_PROJECTION = listOf(
        Regex("\\b(premiers mois|premieres semaines|premiers jours)\\b"),
        Regex("\\bdes (mon|ma|la) (arrivee|prise de (poste|fonction)|integration)\\b"),
        Regex("\\bdans un premier temps,? je\\b"),
        // Le futur et le conditionnel a la premiere personne, sur le travail.
        Regex("\\bje (compte|pourrai|saurai|veillerai|assurerai|mettrai|apporterai|" +
            "deploierai|proposerai|commencerai|organiserai|contribuerai|prendrai)\\b"),
        Regex("\\bj (assurerai|apporterai|organiserai|aurai)\\b"),
        Regex("\\b(cela|ceci|cette approche|mon approche|ma methode|cette methode) " +
            "(me |m )?permettra\\b"),
        Regex("\\bje serai (en mesure|amene)\\b"),
    )

    /**
     * Le plan en etapes, reconnaissable a sa numerotation. Se cherche sur le
     * texte brut : la normalisation efface la ponctuation qui le trahit.
     */
    private val MOTIF_PLAN_NUMEROTE = Regex("""\b1\s*[).]\s*\p{L}.{0,400}?\b2\s*[).]\s*\p{L}""",
        setOf(RegexOption.DOT_MATCHES_ALL))

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
    /**
     * Niveaux de diplome reconnus, du moins eleve au plus eleve. Sert a refuser
     * qu'un intitule du CV soit accepte comme reformulation d'un diplome du
     * profil quand les deux n'annoncent pas le meme niveau.
     */
    private val NIVEAUX = listOf(
        "doctorat" to listOf("doctorat", "phd"),
        "master" to listOf("master", "mastere", "ingenieur", "bac 5"),
        "licence" to listOf("licence", "bachelor", "bac 3"),
        "bac2" to listOf("bts", "dut", "deug", "but", "bac 2"),
        "detat" to listOf("diplome d etat", "diplome etat"),
        "bac" to listOf("baccalaureat", "bac"),
        "cap" to listOf("cap", "bep"),
    )

    private fun niveau(normalise: String): String? =
        NIVEAUX.firstOrNull { (_, formes) -> formes.any { normalise.contains(it) } }?.first

    /** Vrai si les deux intitules n'annoncent pas des niveaux differents. */
    private fun niveauCompatible(a: String, b: String): Boolean {
        val na = niveau(a) ?: return true
        val nb = niveau(b) ?: return true
        return na == nb
    }

    private fun jetonsCommuns(orgNormalisee: String, referenceNormalisee: String): Boolean {
        val jetons = orgNormalisee.split(' ')
            .filter { it.length >= 4 && it !in MOTS_VIDES }
        if (jetons.isEmpty()) return false
        return jetons.any { referenceNormalisee.contains(it) }
    }

    private fun extraireNombres(texte: String): List<String> =
        Regex("\\d[\\d\\s.,]*%?").findAll(texte)
            // Tous les blancs, pas seulement l'espace : le motif accepte \s, donc
            // un "1" en fin de ligne ressortait "1\n". Le retour a la ligne
            // survivait, la chaine faisait deux caracteres, et le garde-fou
            // "un seul chiffre = trop de faux positifs" ne s'appliquait plus.
            // Resultat : un "1" anodin signale comme chiffre invente, retour a
            // la ligne compris, dans le bandeau d'avertissement.
            .map { m -> m.value.filterNot { it.isWhitespace() }.trim('.', ',') }
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
