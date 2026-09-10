package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.SortieVoulue
import com.jobmaker.di.AppContainer
import com.jobmaker.work.EtatGeneration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

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

    private val _sortie = MutableStateFlow(SortieVoulue.LES_DEUX)
    val sortie: StateFlow<SortieVoulue> = _sortie.asStateFlow()

    val etat: StateFlow<EtatGeneration> = moteur.etat

    val profile = container.profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.jobmaker.data.model.Profile())

    val modelesInstalles = container.modelManager.installed

    fun majOffre(texte: String) {
        _offre.value = texte
    }

    fun reinitialiser() = moteur.reinitialiser()

    fun majSortie(valeur: SortieVoulue) {
        _sortie.value = valeur
    }

    fun lancer(candidatureExistante: Candidature? = null) {
        moteur.lancer(_offre.value, _sortie.value, candidatureExistante)
    }

    fun annuler() = moteur.annuler()
}
