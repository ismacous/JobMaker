package com.jobmaker.llm.cloud

import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.MoteurPret
import com.jobmaker.llm.MoteurTexte

/**
 * Le moteur qui delegue le calcul a une API distante gratuite.
 *
 * Gain mesure par rapport au moteur local sur un telephone haut de gamme :
 * une candidature complete passe d'une dizaine de minutes a quelques secondes,
 * pour une qualite de redaction superieure -- un modele de 120 milliards de
 * parametres servi par le fournisseur n'a pas de commune mesure avec un 4B
 * quantifie qui tient dans la memoire d'un telephone.
 *
 * Contrepartie, assumee et affichee dans l'application : le profil et l'offre
 * sortent du telephone. C'est pour cela que le moteur local reste la et que le
 * choix se fait ecran ouvert, pas par defaut silencieux.
 */
class MoteurCloud(
    private val client: ClientCloud,
    val fournisseur: FournisseurCloud,
    private val modele: String,
    /** Fournie a l'appel, jamais conservee en clair sur le disque. */
    private val cle: String,
    /** Remonte a l'ecran ce que l'utilisateur doit savoir pendant la generation. */
    private val onInfo: ((String) -> Unit)? = null,
) : MoteurTexte {

    private val modeleRetenu = modele.ifBlank { fournisseur.modeleParDefaut }

    /**
     * L'attente du quota n'est signalee qu'une fois par generation. Elle se
     * repete a chaque etape sur les offres gratuites les plus serrees, et
     * quatre bandeaux identiques n'apprennent rien de plus que le premier.
     */
    @Volatile
    private var attenteAnnoncee = false

    override val nomCourt: String = fournisseur.nom

    override val distant: Boolean = true

    override suspend fun preparer(role: AgentRole): MoteurPret = MoteurPret(
        nomModele = "$modeleRetenu (${fournisseur.nom})",
        // Les modeles servis par API ont tous au moins 32k de contexte : le
        // profil complet passe toujours, on ne resume donc jamais.
        tailleContexte = CONTEXTE_SUPPOSE,
        // Les API rendent le texte final sans bloc de raisonnement.
        brideRaisonnement = false,
    )

    /**
     * Estimation, et non compte exact : demander le compte exact couterait un
     * aller-retour reseau par etape pour une decision -- profil complet ou
     * resume -- qui se prend tres bien a la louche.
     */
    override suspend fun tokenCount(texte: String): Int = (texte.length / 3.2).toInt() + 1

    override suspend fun completer(
        messages: List<ChatMessage>,
        params: GenerationParams,
        onLecturePrompt: ((Int, Int, Long) -> Unit)?,
        onToken: ((String) -> Unit)?,
    ): String {
        // Aucune phase de lecture de prompt a mesurer : le serveur la fait, et
        // en quelques dizaines de millisecondes. On signale la phase une fois
        // pour que l'ecran passe de "preparation" a "redaction" sans attendre.
        val estimation = messages.sumOf { tokenCount(it.content) }
        onLecturePrompt?.invoke(estimation, estimation, 0L)
        return client.completer(
            fournisseur = fournisseur,
            modele = modeleRetenu,
            cle = cle,
            messages = messages,
            params = params.copy(maxTokens = budget(params.maxTokens)),
            onToken = onToken,
            onAttente = { secondes -> annoncerAttente(secondes) },
        )
    }

    override suspend fun liberer() = Unit

    /**
     * Les quotas gratuits se comptent en tokens par minute, et une
     * candidature complete en depense plusieurs milliers a chaque etape.
     * Attendre le renouvellement de la fenetre vaut mieux que de rendre la main
     * au modele du telephone : quelques dizaines de secondes contre plusieurs
     * minutes, et une bien meilleure redaction.
     */
    private fun annoncerAttente(secondes: Int) {
        if (attenteAnnoncee) return
        attenteAnnoncee = true
        onInfo?.invoke(
            "Quota gratuit de ${fournisseur.nom} atteint : il se compte en tokens par " +
                "minute, et votre annonce en consomme beaucoup. L'application attend " +
                "le renouvellement (environ ${secondes} s) entre les etapes plutot que " +
                "de basculer sur le modele du telephone. C'est normal, la generation " +
                "suit son cours.\n\nPour aller plus vite : un modele plus petit " +
                "(openai/gpt-oss-20b) a un quota par minute plus large."
        )
    }

    /**
     * Les budgets de tokens du pipeline ont ete tailles pour un modele local,
     * ou chaque token coute des secondes de calcul et de la memoire. A
     * distance, aucune des deux contraintes ne s'applique, et un modele a
     * raisonnement depense une partie du budget a reflechir avant d'ecrire une
     * seule ligne. On double donc, sans plafond genant : on ne paie que les
     * tokens reellement produits.
     */
    private fun budget(demande: Int): Int = (demande * 2).coerceIn(1024, 8192)

    private companion object { const val CONTEXTE_SUPPOSE = 32_768 }
}
