package com.jobmaker.llm

import com.jobmaker.llm.cloud.ClientCloud
import com.jobmaker.llm.cloud.FournisseurCloud
import com.jobmaker.llm.cloud.MoteurCloud
import com.jobmaker.llm.local.MoteurLocal

/**
 * Construit le moteur correspondant aux reglages, au debut de chaque pipeline.
 *
 * [lireCle] est passe en parametre plutot que le coffre lui-meme : le paquet
 * llm n'a alors rien a savoir du stockage des preferences, et la cle ne traine
 * pas dans un objet a longue duree de vie -- elle est lue juste avant l'appel.
 */
class FabriqueMoteur(
    private val runtime: LlmRuntime,
    private val modelManager: ModelManager,
    private val client: ClientCloud,
    private val lireCle: suspend (FournisseurCloud) -> String?,
) {

    suspend fun creer(config: ConfigMoteur, onAvertissement: (String) -> Unit): MoteurTexte {
        val local = { MoteurLocal(runtime, modelManager, config) }
        val modeleInstalle = modelManager.installed.value.isNotEmpty()
        if (config.mode == ModeMoteur.APPAREIL) return local()

        // Les cles sont relues a chaque generation : une cle ajoutee dans les
        // reglages entre dans la chaine immediatement.
        val cles = FournisseurCloud.entries
            .mapNotNull { f -> lireCle(f)?.takeIf { it.isNotBlank() }?.let { f to it } }
            .toMap()

        val chaine = ChaineMoteurs.fournisseurs(
            actif = config.fournisseur,
            avecCle = cles.keys,
            enchainer = config.enchainerFournisseurs,
        )

        if (chaine.isEmpty()) {
            if (modeleInstalle) {
                onAvertissement(
                    "Aucune cle enregistree pour ${config.fournisseur.nom} : la generation " +
                        "se fait sur le telephone. Ajoutez votre cle gratuite dans " +
                        "Reglages > Moteur d'IA pour gagner plusieurs minutes."
                )
                return local()
            }
            throw LlmException(
                "Aucune cle d'API pour ${config.fournisseur.nom}, et aucun modele installe " +
                    "sur le telephone. Ouvrez Reglages > Moteur d'IA : la cle est gratuite " +
                    "et prend deux minutes a creer."
            )
        }

        if (chaine.first() != config.fournisseur) {
            onAvertissement(
                "Aucune cle pour ${config.fournisseur.nom} : ${chaine.first().nom} prend " +
                    "sa place pour cette generation."
            )
        }

        // La chaine se monte par la fin : chaque fournisseur enveloppe tout ce
        // qui vient apres lui. Groq(Gemini(telephone)) plutot qu'une boucle de
        // secours a ecrire -- MoteurAvecRepli sait deja passer au suivant, il
        // suffit de l'emboiter.
        var moteur: MoteurTexte? =
            if (config.repliLocal && modeleInstalle) local() else null

        for (f in chaine.reversed()) {
            val distant = MoteurCloud(
                client = client,
                fournisseur = f,
                modele = config.modelePour(f),
                cle = cles.getValue(f),
                onInfo = onAvertissement,
                // Seul le dernier fournisseur patiente sur un quota epuise :
                // tant qu'il en reste un autre, basculer est instantane alors
                // qu'attendre coute une minute.
                dernierDeLaChaine = f == chaine.last(),
            )
            moteur = moteur?.let { MoteurAvecRepli(distant, it, onAvertissement) } ?: distant
        }

        return moteur ?: local()
    }
}
