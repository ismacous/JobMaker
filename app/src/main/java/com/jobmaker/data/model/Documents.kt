package com.jobmaker.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

// ---------------------------------------------------------------------------
// Sorties des agents. Les noms de champs sont en francais parce qu'ils sont
// donnes tels quels au modele comme schema a respecter : plus le schema est
// proche de la langue de travail, moins un petit modele se trompe.
// ---------------------------------------------------------------------------

/** Etape 1 : ce que l'annonce demande vraiment. */
@Serializable
data class JobAnalysis(
    val poste: String = "",
    val entreprise: String = "",
    val lieu: String = "",
    val contrat: String = "",
    val secteur: String = "",
    /** debutant / junior / confirme / senior */
    val seniorite: String = "",
    /** Code langue de l'annonce : "fr", "en"... Determine la langue du CV. */
    val langue: String = "fr",
    val missions: List<String> = emptyList(),
    val competencesRequises: List<String> = emptyList(),
    val competencesSouhaitees: List<String> = emptyList(),
    val outils: List<String> = emptyList(),
    val softSkills: List<String> = emptyList(),
    /** Termes a replacer mot pour mot : les filtres automatiques les cherchent. */
    val motsClesAts: List<String> = emptyList(),
    /** Ce que l'annonce ne dit pas mais attend quand meme. */
    val attentesImplicites: List<String> = emptyList(),
    val remuneration: String = "",
    /** Vrai quand l'annonce est trop maigre et que l'analyse a du deduire. */
    val annonceIncomplete: Boolean = false,
)

/** Etape 2 : le plan de bataille avant d'ecrire une ligne. */
@Serializable
data class Strategy(
    val angle: String = "",
    val titreCvSuggere: String = "",
    val ton: String = "",
    val experiencesPrioritaires: List<PrioriteExperience> = emptyList(),
    val competencesAAfficher: List<String> = emptyList(),
    val motsClesAPlacer: List<String> = emptyList(),
    val ecarts: List<Ecart> = emptyList(),
    val argumentsCles: List<String> = emptyList(),
)

@Serializable
data class PrioriteExperience(
    val experienceId: String = "",
    val intitule: String = "",
    val raison: String = "",
    val pointsAMettreEnAvant: List<String> = emptyList(),
)

@Serializable
data class Ecart(
    val ecart: String = "",
    /** Comment le compenser honnetement, sans mentir. */
    val reponse: String = "",
)

/** Etape 3 : le contenu du CV, pret a etre mis en page. */
@Serializable
data class CvContent(
    val langue: String = "fr",
    val titre: String = "",
    val accroche: String = "",
    val experiences: List<CvExperience> = emptyList(),
    val formations: List<CvFormation> = emptyList(),
    val competences: List<CvGroupeCompetences> = emptyList(),
    val langues: List<CvLangue> = emptyList(),
    val certifications: List<String> = emptyList(),
    val projets: List<CvProjet> = emptyList(),
    val centresInteret: List<String> = emptyList(),
    val infosComplementaires: List<String> = emptyList(),
) {
    /** Tout le texte du CV, pour les controles automatiques. */
    fun texteIntegral(): String = buildString {
        appendLine(titre); appendLine(accroche)
        experiences.forEach { e ->
            appendLine("${e.poste} ${e.entreprise} ${e.lieu} ${e.periode}")
            e.puces.forEach { appendLine(it) }
        }
        formations.forEach { appendLine("${it.diplome} ${it.etablissement} ${it.periode}") }
        competences.forEach { appendLine("${it.categorie}: ${it.items.joinToString(", ")}") }
        langues.forEach { appendLine("${it.nom} ${it.niveau}") }
        certifications.forEach { appendLine(it) }
        projets.forEach { appendLine("${it.nom} ${it.description}") }
        centresInteret.forEach { appendLine(it) }
        infosComplementaires.forEach { appendLine(it) }
    }
}

@Serializable
data class CvExperience(
    val poste: String = "",
    val entreprise: String = "",
    val lieu: String = "",
    val periode: String = "",
    val puces: List<String> = emptyList(),
)

@Serializable
data class CvFormation(
    val diplome: String = "",
    val etablissement: String = "",
    val lieu: String = "",
    val periode: String = "",
    val detail: String = "",
)

@Serializable
data class CvGroupeCompetences(
    val categorie: String = "",
    val items: List<String> = emptyList(),
)

@Serializable
data class CvLangue(val nom: String = "", val niveau: String = "")

@Serializable
data class CvProjet(val nom: String = "", val description: String = "")

/** Etape 4 : la lettre de motivation. */
@Serializable
data class LetterContent(
    val langue: String = "fr",
    val objet: String = "",
    val destinataire: String = "",
    val lieuEtDate: String = "",
    val salutation: String = "Madame, Monsieur,",
    val paragraphes: List<String> = emptyList(),
    val formulePolitesse: String = "",
    val signature: String = "",
) {
    fun texteIntegral(): String =
        (listOf(objet, salutation) + paragraphes + listOf(formulePolitesse, signature))
            .filter { it.isNotBlank() }.joinToString("\n\n")
}

/** Etape 5 : le rapport de relecture. */
/**
 * Sortie de la premiere etape du pipeline : analyse de l'annonce et strategie
 * de candidature, produites en un seul appel.
 *
 * Les deux etaient separees a l'origine, ce qui obligeait le modele a relire
 * l'annonce et le profil une seconde fois pour un gain nul : la strategie
 * decoule directement de l'analyse.
 */
@Serializable
data class DossierPreparation(
    val analyse: JobAnalysis = JobAnalysis(),
    val strategie: Strategy = Strategy(),
)

/**
 * Sortie de la seconde etape : le CV et la lettre, ecrits en un seul appel a
 * partir du meme dossier. Les separer coutait une relecture complete du
 * contexte -- plusieurs minutes sur un telephone.
 */
/**
 * Sortie de l'appel unique : tout ce qu'une candidature demande, produit d'une
 * traite.
 *
 * Deux appels successifs coutaient deux fois le prompt -- consignes, annonce et
 * profil relus depuis zero -- plus la mise par ecrit d'une analyse dont le seul
 * lecteur etait l'appel suivant. Mesure sur un S25 Ultra : 8300 tokens lus et
 * 2400 ecrits pour 1500 tokens utiles.
 *
 * L'ordre des champs est le raisonnement : le modele constate d'abord ce que
 * l'annonce demande, en tire un angle, et n'ecrit qu'ensuite. Il reflechit donc
 * toujours avant de rediger, mais en trois cents tokens au lieu de mille deux
 * cents, et sans avoir a tout relire pour s'en servir.
 */
@Serializable
data class DossierComplet(
    val analyse: JobAnalysis = JobAnalysis(),
    val strategie: Strategy = Strategy(),
    val cv: CvContent = CvContent(),
    val lettre: LetterContent = LetterContent(),
)

@Serializable
data class DocumentsRediges(
    val cv: CvContent = CvContent(),
    val lettre: LetterContent = LetterContent(),
)

@Serializable
data class Review(
    val scoreGlobal: Int = 0,
    /** Elements presents dans le CV mais absents du profil : a corriger d'office. */
    val faitsInventes: List<String> = emptyList(),
    val motsClesManquants: List<String> = emptyList(),
    val problemes: List<Probleme> = emptyList(),
    val pointsForts: List<String> = emptyList(),
    val verdict: String = "",
)

@Serializable
data class Probleme(
    /** bloquant / important / mineur */
    val gravite: String = "mineur",
    val zone: String = "",
    val probleme: String = "",
    val correction: String = "",
)

/** Resultat de l'onglet "Comprendre un poste". */
@Serializable
data class JobExplanation(
    val intituleClair: String = "",
    val resumeSimple: String = "",
    val cestQuoiConcretement: String = "",
    val journeeType: List<String> = emptyList(),
    val competencesCles: List<String> = emptyList(),
    val outils: List<String> = emptyList(),
    val formationTypique: String = "",
    val salaireIndicatif: String = "",
    val conditionsTravail: String = "",
    val evolutions: List<String> = emptyList(),
    val signauxPositifs: List<String> = emptyList(),
    val pointsDeVigilance: List<String> = emptyList(),
    val questionsAPoser: List<String> = emptyList(),
    val adequation: Adequation = Adequation(),
    val vocabulaire: List<TermeExplique> = emptyList(),
)

@Serializable
data class Adequation(
    val score: Int = 0,
    val atouts: List<String> = emptyList(),
    val manques: List<String> = emptyList(),
    val conseils: List<String> = emptyList(),
)

@Serializable
data class TermeExplique(val terme: String = "", val explication: String = "")

// ---------------------------------------------------------------------------
// Dossier de candidature : tout ce qui est produit pour une offre donnee.
// ---------------------------------------------------------------------------

@Serializable
data class Candidature(
    val id: String = UUID.randomUUID().toString(),
    val offreTexte: String = "",
    val analyse: JobAnalysis = JobAnalysis(),
    val strategie: Strategy = Strategy(),
    val cv: CvContent = CvContent(),
    val lettre: LetterContent = LetterContent(),
    val revue: Review = Review(),
    val gabarit: String = "sobre",
    val couleurAccent: String = "#1F4E79",
    val avecPhoto: Boolean = false,
    /** Score de couverture des mots-cles, calcule sans IA. */
    val scoreAts: Int = 0,
    val statut: StatutCandidature = StatutCandidature.BROUILLON,
    val creeLe: Long = System.currentTimeMillis(),
    val modifieLe: Long = System.currentTimeMillis(),
    val notesPerso: String = "",
)

enum class StatutCandidature(val label: String) {
    BROUILLON("Brouillon"),
    PRETE("Prete a envoyer"),
    ENVOYEE("Envoyee"),
    ENTRETIEN("Entretien obtenu"),
    REFUSEE("Refusee"),
    ACCEPTEE("Acceptee"),
}
