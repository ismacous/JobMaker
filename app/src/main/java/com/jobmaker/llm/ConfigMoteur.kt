package com.jobmaker.llm

import com.jobmaker.llm.cloud.FournisseurCloud

/** Ou tourne le calcul. */
enum class ModeMoteur(val label: String, val resume: String) {
    APPAREIL(
        "Sur l'appareil",
        "Rien ne sort du telephone. Comptez plusieurs minutes par candidature.",
    ),
    CLOUD(
        "API gratuite",
        "Quelques secondes par candidature. Le profil et l'offre partent chez le fournisseur.",
    ),
}

/**
 * Tout ce dont la fabrique a besoin pour construire un moteur, extrait des
 * reglages.
 *
 * Exister separement de [com.jobmaker.data.prefs.Settings] evite que le paquet
 * llm depende du stockage des preferences : le moteur se construit aussi bien
 * depuis un test que depuis l'application.
 */
data class ConfigMoteur(
    val mode: ModeMoteur = ModeMoteur.CLOUD,
    val fournisseur: FournisseurCloud = FournisseurCloud.GROQ,
    /** Modele retenu chez chaque fournisseur. Absent = modele par defaut. */
    val modelesCloud: Map<FournisseurCloud, String> = emptyMap(),
    /**
     * Passe au fournisseur suivant quand celui en cours sature, plutot que de
     * rendre la main au telephone alors qu'une autre cle attend sans servir.
     */
    val enchainerFournisseurs: Boolean = true,
    /**
     * Bascule sur le modele local quand plus aucun fournisseur ne repond.
     * Dernier maillon de la chaine, jamais le premier.
     */
    val repliLocal: Boolean = true,
    val modeleParRole: Map<AgentRole, String> = emptyMap(),
    val tailleContexte: Int = 6144,
    val threads: Int = LlmRuntime.defaultThreads(),
    val couchesGpu: Int = 0,
    val chargerEnMemoire: Boolean = false,
) {
    fun modelePour(f: FournisseurCloud): String =
        modelesCloud[f]?.takeIf { it.isNotBlank() } ?: f.modeleParDefaut
}

/**
 * Dans quel ordre essayer les fournisseurs.
 *
 * Fonction pure, et volontairement partagee entre la fabrique et l'ecran de
 * reglages : l'utilisateur doit voir exactement la chaine qui sera suivie, pas
 * une description qui pourrait deriver du comportement reel.
 */
object ChaineMoteurs {

    /**
     * @param actif le fournisseur choisi, toujours en tete quand il a une cle.
     * @param avecCle ceux dont une cle est enregistree ; les autres ne servent
     *   a rien dans une chaine.
     * @param enchainer false limite la chaine au seul fournisseur choisi.
     */
    fun fournisseurs(
        actif: FournisseurCloud,
        avecCle: Set<FournisseurCloud>,
        enchainer: Boolean,
    ): List<FournisseurCloud> {
        val tete = listOfNotNull(actif.takeIf { it in avecCle })
        if (!enchainer) return tete
        // Le choix de l'utilisateur d'abord, puis les autres dans l'ordre ou
        // l'ecran les presente : ce qu'il voit est ce qui se passe.
        val suivants = FournisseurCloud.entries.filter { it != actif && it in avecCle }
        return tete + suivants
    }
}
