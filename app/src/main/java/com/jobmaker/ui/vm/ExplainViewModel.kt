package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.agents.PipelineEvent
import com.jobmaker.data.model.JobExplanation
import com.jobmaker.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EtatExplication(
    val enCours: Boolean = false,
    val statut: String = "",
    val modele: String = "",
    val apercuBrut: String = "",
    val resultat: JobExplanation? = null,
    val erreur: String? = null,
)

/** Onglet "Comprendre un poste". */
class ExplainViewModel(private val container: AppContainer) : ViewModel() {

    private val _texte = MutableStateFlow("")
    val texte: StateFlow<String> = _texte.asStateFlow()

    private val _etat = MutableStateFlow(EtatExplication())
    val etat: StateFlow<EtatExplication> = _etat.asStateFlow()

    val historique = container.explanationRepository.all
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var travail: Job? = null

    fun majTexte(v: String) { _texte.value = v }

    fun effacer() { _etat.value = EtatExplication() }

    fun lancer() {
        if (_etat.value.enCours) return
        travail?.cancel()
        _etat.value = EtatExplication(enCours = true, statut = "Preparation...")

        travail = viewModelScope.launch {
            val profil = container.profileRepository.get()
            val reglages = container.settingsRepository.settings.first()

            container.orchestrator.expliquerPoste(_texte.value, profil, reglages).collect { ev ->
                when (ev) {
                    is PipelineEvent.Etape ->
                        _etat.value = _etat.value.copy(statut = ev.detail)
                    is PipelineEvent.Modele ->
                        _etat.value = _etat.value.copy(modele = ev.nom)
                    is PipelineEvent.Jeton ->
                        _etat.value = _etat.value.copy(
                            apercuBrut = (_etat.value.apercuBrut + ev.texte).takeLast(1200)
                        )
                    is PipelineEvent.ExplicationPrete -> {
                        container.explanationRepository.save(_texte.value, ev.explication)
                        container.llmRuntime.unload()
                        _etat.value = _etat.value.copy(
                            enCours = false, resultat = ev.explication, statut = "Termine",
                        )
                    }
                    is PipelineEvent.Echec -> {
                        container.llmRuntime.unload()
                        _etat.value = _etat.value.copy(enCours = false, erreur = ev.message)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun annuler() {
        travail?.cancel()
        viewModelScope.launch { container.llmRuntime.unload() }
        _etat.value = _etat.value.copy(enCours = false, statut = "Interrompu")
    }

    fun ouvrirDepuisHistorique(entree: com.jobmaker.data.repo.ExplanationRepository.Entry) {
        _texte.value = entree.offreTexte
        _etat.value = EtatExplication(resultat = entree.explication)
    }

    fun supprimerHistorique(id: String) {
        viewModelScope.launch { container.explanationRepository.delete(id) }
    }
}
