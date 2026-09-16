package com.jobmaker.llm

/**
 * Ce qu'un moteur annonce une fois pret a servir une etape du pipeline.
 */
data class MoteurPret(
    /** Nom affiche a l'ecran pendant la generation. */
    val nomModele: String,
    /** Fenetre de contexte utilisable, en tokens. */
    val tailleContexte: Int,
    /** Vrai si le modele emet des blocs de raisonnement qu'il faut brider. */
    val brideRaisonnement: Boolean,
)

/**
 * Source de texte du pipeline, quelle qu'en soit l'implementation.
 *
 * Deux realisations coexistent :
 *  - [com.jobmaker.llm.local.MoteurLocal] fait tourner un GGUF sur l'appareil.
 *    Aucune donnee ne sort du telephone, mais une candidature demande une
 *    dizaine de minutes et vide la batterie.
 *  - [com.jobmaker.llm.cloud.MoteurCloud] delegue le calcul a une API distante
 *    gratuite. La meme candidature sort en quelques secondes, au prix d'un
 *    envoi du profil et de l'offre chez le fournisseur choisi.
 *
 * L'orchestrateur ne connait que cette interface : c'est ce qui permet de
 * changer de moteur dans les reglages sans toucher aux agents.
 */
interface MoteurTexte {

    /** Libelle court du moteur, pour l'interface. */
    val nomCourt: String

    /** Vrai si les donnees envoyees quittent l'appareil. */
    val distant: Boolean

    /**
     * Prepare le moteur pour [role] : chargement du GGUF en local, choix du
     * modele distant en cloud. Appele avant chaque etape.
     */
    suspend fun preparer(role: AgentRole): MoteurPret

    /** Compte les tokens de [texte], exactement ou par estimation. */
    suspend fun tokenCount(texte: String): Int

    /**
     * Produit la reponse complete.
     *
     * @param onLecturePrompt avancement de la lecture du prompt (lus, total, ms).
     *   Un moteur distant ne peut pas la mesurer : il signale la phase une fois.
     * @param onToken recoit les morceaux au fil de l'eau.
     */
    suspend fun completer(
        messages: List<ChatMessage>,
        params: GenerationParams,
        onLecturePrompt: ((lus: Int, total: Int, dureeMs: Long) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null,
    ): String

    /** Libere ce qui peut l'etre (memoire du modele local). */
    suspend fun liberer()
}
