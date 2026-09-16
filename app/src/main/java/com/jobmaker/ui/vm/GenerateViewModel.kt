package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.data.model.Candidature
import com.jobmaker.di.AppContainer
import com.jobmaker.llm.ModeMoteur
import com.jobmaker.work.EtatGeneration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Ce que l'ecran de generation doit dire avant de lancer : est-ce que ca va
 * marcher, combien de temps ca va prendre, et ou partent les donnees.
 */
data class ApercuMoteur(
    val pret: Boolean = false,
    val resume: String = "",
    val alerte: String? = null,
)

/**
 * Ecran "Nouvelle candidature" : coller une offre, lancer le pipeline.
 *
 * Ne detient plus la generation : elle vit dans [com.jobmaker.work.MoteurCandidature],
 * qui survit a la fermeture de l'ecran et de l'application. Ce ViewModel n'est
 * qu'une vue dessus, ce qui permet de revenir sur l'ecran en cours de route et
 * de retrouver l'avancement exact.
 */
class GenerateViewModel(private val container: AppContainer) : ViewModel() {

    private val moteur = container.moteurCandidature

    private val _offre = MutableStateFlow("")
    val offre: StateFlow<String> = _offre.asStateFlow()

    val etat: StateFlow<EtatGeneration> = moteur.etat

    val profile = container.profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.jobmaker.data.model.Profile())

    /**
     * Etat du moteur, recalcule a chaque changement de reglage, de cle ou de
     * modele installe. Le bouton "Generer" en depend : mieux vaut expliquer
     * avant qu'apres dix minutes d'attente.
     */
    val apercuMoteur: StateFlow<ApercuMoteur> = combine(
        container.settingsRepository.settings,
        container.coffreCles.empreintes,
        container.modelManager.installed,
    ) { reglages, cles, modeles ->
        val cloud = reglages.modeMoteur == ModeMoteur.CLOUD
        val fournisseur = reglages.fournisseurCloud
        val cleEnregistree = cles.containsKey(fournisseur)
        when {
            cloud && cleEnregistree -> ApercuMoteur(
                pret = true,
                resume = "Redige par ${fournisseur.nom} : comptez quelques secondes. " +
                    "L'offre et le resume de votre profil lui sont envoyes ; vos " +
                    "coordonnees et votre photo restent sur le telephone.",
            )

            cloud && modeles.isNotEmpty() -> ApercuMoteur(
                pret = true,
                resume = "Generation sur le telephone : comptez 2 a 10 minutes.",
                alerte = "Aucune cle ${fournisseur.nom} enregistree : la generation se " +
                    "fera sur le telephone. Une cle gratuite ramene le temps d'attente " +
                    "a quelques secondes.",
            )

            cloud -> ApercuMoteur(
                pret = false,
                alerte = "Aucune cle d'API et aucun modele installe : l'application ne " +
                    "peut rien generer. Le plus rapide est d'enregistrer une cle " +
                    "gratuite ; le plus discret, de telecharger un modele.",
            )

            modeles.isNotEmpty() -> ApercuMoteur(
                pret = true,
                resume = "Tout se passe sur le telephone : comptez 2 a 10 minutes selon le " +
                    "modele. Vous pouvez quitter l'application, la generation continue et " +
                    "vous previent quand c'est pret.",
            )

            else -> ApercuMoteur(
                pret = false,
                alerte = "Aucun modele d'IA n'est installe. Telechargez-en un (une seule " +
                    "fois, en Wi-Fi), ou passez le moteur sur une API gratuite.",
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ApercuMoteur())

    fun majOffre(texte: String) {
        _offre.value = texte
    }

    fun reinitialiser() = moteur.reinitialiser()

    fun lancer(candidatureExistante: Candidature? = null) {
        moteur.lancer(_offre.value, candidatureExistante)
    }

    fun annuler() = moteur.annuler()
}
