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
        if (config.mode == ModeMoteur.APPAREIL) return local()

        val cle = lireCle(config.fournisseur)?.takeIf { it.isNotBlank() }
        if (cle == null) {
            if (modelManager.installed.value.isNotEmpty()) {
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

        val distant = MoteurCloud(
            client = client,
            fournisseur = config.fournisseur,
            modele = config.modeleCloud,
            cle = cle,
            onInfo = onAvertissement,
        )

        val repliDisponible = config.repliLocal && modelManager.installed.value.isNotEmpty()
        return if (repliDisponible) {
            MoteurAvecRepli(distant, local(), onAvertissement)
        } else {
            distant
        }
    }
}
