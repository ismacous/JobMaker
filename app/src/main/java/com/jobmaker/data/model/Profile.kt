package com.jobmaker.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Tout ce que l'utilisateur saisit une fois pour toutes sur lui-meme.
 *
 * C'est la seule source de verite factuelle de l'application : les agents de
 * redaction peuvent reformuler, trier et traduire ce contenu, jamais y ajouter
 * un employeur, un diplome ou un chiffre qui n'y figure pas.
 *
 * Le profil est stocke en un seul document JSON (une ligne en base) plutot
 * qu'eclate en dix tables : il n'y a qu'un utilisateur, et le JSON est de
 * toute facon le format dans lequel il part vers le modele.
 */
@Serializable
data class Profile(
    val identite: Identite = Identite(),
    /** Presentation libre. Sert de matiere premiere au resume du CV. */
    val presentation: String = "",
    val objectifPro: String = "",
    val experiences: List<Experience> = emptyList(),
    val formations: List<Formation> = emptyList(),
    val competences: List<GroupeCompetences> = emptyList(),
    val langues: List<Langue> = emptyList(),
    val certifications: List<Certification> = emptyList(),
    val projets: List<Projet> = emptyList(),
    val benevolat: List<Experience> = emptyList(),
    val permis: List<String> = emptyList(),
    val centresInteret: List<String> = emptyList(),
    val liens: List<Lien> = emptyList(),
    /** Contexte de recherche : ne s'imprime pas, mais oriente la redaction. */
    val recherche: ContexteRecherche = ContexteRecherche(),
) {
    /** Le profil est-il assez rempli pour esperer un resultat correct ? */
    val completude: Int
        get() {
            var score = 0
            if (identite.prenom.isNotBlank() && identite.nom.isNotBlank()) score += 15
            if (identite.email.isNotBlank() || identite.telephone.isNotBlank()) score += 10
            if (identite.ville.isNotBlank()) score += 5
            if (presentation.length > 80) score += 10
            if (experiences.isNotEmpty()) score += 20
            if (experiences.any { it.missions.isNotEmpty() || it.realisations.isNotEmpty() }) score += 10
            if (formations.isNotEmpty()) score += 15
            if (competences.any { it.items.isNotEmpty() }) score += 10
            if (langues.isNotEmpty()) score += 5
            return score.coerceAtMost(100)
        }

    val manques: List<String>
        get() = buildList {
            if (identite.prenom.isBlank() || identite.nom.isBlank()) add("Nom et prenom")
            if (identite.email.isBlank() && identite.telephone.isBlank())
                add("Au moins un moyen de contact (email ou telephone)")
            if (identite.ville.isBlank()) add("Ville (les recruteurs filtrent dessus)")
            if (experiences.isEmpty()) add("Au moins une experience (job d'ete, stage, benevolat : tout compte)")
            if (formations.isEmpty()) add("Votre parcours scolaire")
            if (competences.all { it.items.isEmpty() }) add("Vos competences")
            if (presentation.length < 80)
                add("Une presentation de quelques lignes sur vous (c'est ce qui donne le plus de matiere a l'IA)")
            if (experiences.isNotEmpty() && experiences.all { it.realisations.isEmpty() })
                add("Des resultats concrets dans vos experiences (chiffres, volumes, ameliorations)")
        }
}

@Serializable
data class Identite(
    val prenom: String = "",
    val nom: String = "",
    val titre: String = "",
    val email: String = "",
    val telephone: String = "",
    val adresse: String = "",
    val codePostal: String = "",
    val ville: String = "",
    val pays: String = "France",
    val dateNaissance: String = "",
    val nationalite: String = "",
    /** URI de la photo choisie dans la galerie, vide si pas de photo. */
    val photoUri: String = "",
) {
    val nomComplet: String get() = listOf(prenom, nom).filter { it.isNotBlank() }.joinToString(" ")
    val localisation: String
        get() = listOf(codePostal, ville).filter { it.isNotBlank() }.joinToString(" ")
}

@Serializable
data class Experience(
    val id: String = UUID.randomUUID().toString(),
    val poste: String = "",
    val entreprise: String = "",
    val secteur: String = "",
    val lieu: String = "",
    val typeContrat: String = "",
    val dateDebut: String = "",
    val dateFin: String = "",
    val enCours: Boolean = false,
    /** Ce que vous faisiez au quotidien. */
    val missions: List<String> = emptyList(),
    /** Ce que vous avez obtenu. Chiffre si possible : c'est ce qui fait la difference. */
    val realisations: List<String> = emptyList(),
    val outils: List<String> = emptyList(),
    /** Taille d'equipe, budget, volume : le contexte credibilise le reste. */
    val contexte: String = "",
) {
    val periode: String
        get() = when {
            dateDebut.isBlank() && dateFin.isBlank() -> ""
            enCours -> "$dateDebut - aujourd'hui"
            dateFin.isBlank() -> dateDebut
            else -> "$dateDebut - $dateFin"
        }
    val resumeLigne: String
        get() = listOf(poste, entreprise).filter { it.isNotBlank() }.joinToString(" chez ")
}

@Serializable
data class Formation(
    val id: String = UUID.randomUUID().toString(),
    val diplome: String = "",
    val etablissement: String = "",
    val lieu: String = "",
    val dateDebut: String = "",
    val dateFin: String = "",
    val enCours: Boolean = false,
    val niveau: String = "",
    val mention: String = "",
    val matieres: List<String> = emptyList(),
) {
    val periode: String
        get() = when {
            enCours -> "$dateDebut - en cours"
            dateDebut.isNotBlank() && dateFin.isNotBlank() -> "$dateDebut - $dateFin"
            else -> dateFin.ifBlank { dateDebut }
        }
}

@Serializable
data class GroupeCompetences(
    val id: String = UUID.randomUUID().toString(),
    val categorie: String = "",
    val items: List<Competence> = emptyList(),
)

@Serializable
data class Competence(
    val nom: String = "",
    /** 1 = notions, 5 = expert. 0 = non precise. */
    val niveau: Int = 0,
    val anneesExperience: Int = 0,
)

@Serializable
data class Langue(
    val id: String = UUID.randomUUID().toString(),
    val nom: String = "",
    /** Langue maternelle, C2, B2... */
    val niveau: String = "",
    val certification: String = "",
)

@Serializable
data class Certification(
    val id: String = UUID.randomUUID().toString(),
    val nom: String = "",
    val organisme: String = "",
    val annee: String = "",
)

@Serializable
data class Projet(
    val id: String = UUID.randomUUID().toString(),
    val nom: String = "",
    val description: String = "",
    val role: String = "",
    val outils: List<String> = emptyList(),
    val lien: String = "",
)

@Serializable
data class Lien(
    val id: String = UUID.randomUUID().toString(),
    val libelle: String = "",
    val url: String = "",
)

@Serializable
data class ContexteRecherche(
    val posteVise: String = "",
    val secteursVises: List<String> = emptyList(),
    val disponibilite: String = "",
    val mobilite: String = "",
    val teletravail: String = "",
    val pretentionsSalariales: String = "",
    /** Contraintes a respecter : horaires, sante, garde d'enfants... */
    val contraintes: String = "",
    val vehicule: Boolean = false,
)
