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
    /** Vide = modele par defaut du fournisseur. */
    val modeleCloud: String = "",
    /**
     * Bascule automatiquement sur le modele local quand le distant est
     * injoignable ou a quota epuise.
     */
    val repliLocal: Boolean = true,
    val modeleParRole: Map<AgentRole, String> = emptyMap(),
    val tailleContexte: Int = 6144,
    val threads: Int = LlmRuntime.defaultThreads(),
    val couchesGpu: Int = 0,
    val chargerEnMemoire: Boolean = false,
)
