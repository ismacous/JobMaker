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

/**
 * Ou en est le modele. La distinction compte : la lecture du prompt est une
 * phase pendant laquelle rien ne s'ecrit, et sans la nommer on croit
 * l'application figee.
 */
enum class PhaseGeneration(val libelle: String) {
    PREPARATION("Chargement du modele"),
    LECTURE("Lecture de l'annonce et du profil"),
    REDACTION("Redaction"),
}

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
    // --- mesures ---
    val phase: PhaseGeneration = PhaseGeneration.PREPARATION,
    /** Horodatage du lancement, 0 si rien n'a demarre. */
    val debutMs: Long = 0L,
    val promptLus: Int = 0,
    val promptTotal: Int = 0,
    val msLecturePrompt: Long = 0L,
    val tokensEcrits: Int = 0,
    val debutRedactionMs: Long = 0L,
) {
    val progression: Float
        get() = if (etapesTotal == 0) 0f else etapeIndex.toFloat() / etapesTotal

    /** Vitesse de lecture du prompt, en tokens par seconde. */
    val vitesseLecture: Double
        get() = if (msLecturePrompt <= 0) 0.0 else promptLus * 1000.0 / msLecturePrompt

    /** Vitesse de redaction, en tokens par seconde. */
    fun vitesseRedaction(maintenantMs: Long): Double {
        if (debutRedactionMs <= 0L || tokensEcrits <= 0) return 0.0
        val ecoule = maintenantMs - debutRedactionMs
        return if (ecoule <= 0) 0.0 else tokensEcrits * 1000.0 / ecoule
    }

    /** Avancement de la lecture du prompt, de 0 a 1. */
    val progressionLecture: Float
        get() = if (promptTotal <= 0) 0f else (promptLus.toFloat() / promptTotal).coerceIn(0f, 1f)
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
        _etat.value = EtatGeneration(
            enCours = true,
            etapeTitre = "Preparation...",
            debutMs = System.currentTimeMillis(),
        )

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
                // Chaque etape repart de zero : les mesures de la precedente
                // n'ont plus de sens.
                phase = PhaseGeneration.PREPARATION,
                promptLus = 0,
                promptTotal = 0,
                msLecturePrompt = 0L,
                tokensEcrits = 0,
                debutRedactionMs = 0L,
            )

            is PipelineEvent.Modele -> _etat.value = _etat.value.copy(modeleActuel = evenement.nom)

            is PipelineEvent.Lecture -> _etat.value = _etat.value.copy(
                phase = PhaseGeneration.LECTURE,
                promptLus = evenement.lus,
                promptTotal = evenement.total,
                msLecturePrompt = evenement.dureeMs,
            )

            is PipelineEvent.Jeton -> {
                val actuel = _etat.value
                // On ne garde qu'une fenetre glissante : le texte brut sert de
                // temoin d'activite, pas de contenu a lire.
                val nouveau = (actuel.apercuBrut + evenement.texte).takeLast(1200)
                _etat.value = actuel.copy(
                    apercuBrut = nouveau,
                    phase = PhaseGeneration.REDACTION,
                    tokensEcrits = actuel.tokensEcrits + 1,
                    debutRedactionMs = if (actuel.debutRedactionMs == 0L) {
                        System.currentTimeMillis()
                    } else {
                        actuel.debutRedactionMs
                    },
                )
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
