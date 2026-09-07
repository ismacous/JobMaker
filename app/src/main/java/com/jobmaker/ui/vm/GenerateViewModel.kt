package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.agents.PipelineEvent
import com.jobmaker.data.model.Candidature
import com.jobmaker.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EtatGeneration(
    val enCours: Boolean = false,
    val etapeIndex: Int = 0,
    val etapesTotal: Int = 6,
    val etapeTitre: String = "",
    val etapeDetail: String = "",
    val modeleActuel: String = "",
    /** Derniers tokens produits, pour montrer que ca avance. */
    val apercuBrut: String = "",
    val avertissements: List<String> = emptyList(),
    val erreur: String? = null,
    val candidatureId: String? = null,
) {
    val progression: Float
        get() = if (etapesTotal == 0) 0f else etapeIndex.toFloat() / etapesTotal
}

/** Ecran "Nouvelle candidature" : coller une offre, lancer le pipeline. */
class GenerateViewModel(private val container: AppContainer) : ViewModel() {

    private val _offre = MutableStateFlow("")
    val offre: StateFlow<String> = _offre.asStateFlow()

    private val _etat = MutableStateFlow(EtatGeneration())
    val etat: StateFlow<EtatGeneration> = _etat.asStateFlow()

    val profile = container.profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.jobmaker.data.model.Profile())

    val modelesInstalles = container.modelManager.installed

    private var travail: Job? = null

    fun majOffre(texte: String) {
        _offre.value = texte
    }

    fun reinitialiser() {
        _etat.value = EtatGeneration()
    }

    fun lancer(candidatureExistante: Candidature? = null) {
        if (_etat.value.enCours) return
        travail?.cancel()
        _etat.value = EtatGeneration(enCours = true, etapeTitre = "Preparation...")

        travail = viewModelScope.launch {
            val profil = container.profileRepository.get()
            val reglages = container.settingsRepository.settings.first()

            container.orchestrator
                .genererCandidature(_offre.value, profil, reglages, candidatureExistante)
                .collect { evenement -> appliquer(evenement) }
        }
    }

    fun annuler() {
        travail?.cancel()
        travail = null
        viewModelScope.launch { container.llmRuntime.unload() }
        _etat.value = _etat.value.copy(
            enCours = false,
            etapeTitre = "Generation interrompue",
            etapeDetail = "",
        )
    }

    private suspend fun appliquer(evenement: PipelineEvent) {
        when (evenement) {
            is PipelineEvent.Etape -> _etat.value = _etat.value.copy(
                etapeIndex = evenement.index,
                etapesTotal = evenement.total,
                etapeTitre = evenement.titre,
                etapeDetail = evenement.detail,
                apercuBrut = "",
            )

            is PipelineEvent.Modele -> _etat.value = _etat.value.copy(modeleActuel = evenement.nom)

            is PipelineEvent.Jeton -> {
                // On ne garde qu'une fenetre glissante : le texte brut sert de
                // temoin d'activite, pas de contenu a lire.
                val nouveau = (_etat.value.apercuBrut + evenement.texte).takeLast(1200)
                _etat.value = _etat.value.copy(apercuBrut = nouveau)
            }

            is PipelineEvent.Avertissement -> _etat.value = _etat.value.copy(
                avertissements = _etat.value.avertissements + evenement.message,
            )

            is PipelineEvent.CandidaturePrete -> {
                container.candidatureRepository.save(evenement.candidature)
                container.llmRuntime.unload()
                _etat.value = _etat.value.copy(
                    enCours = false,
                    etapeIndex = _etat.value.etapesTotal,
                    etapeTitre = "Termine",
                    etapeDetail = "",
                    candidatureId = evenement.candidature.id,
                )
            }

            is PipelineEvent.Echec -> {
                container.llmRuntime.unload()
                _etat.value = _etat.value.copy(enCours = false, erreur = evenement.message)
            }

            is PipelineEvent.ExplicationPrete -> Unit
        }
    }
}
