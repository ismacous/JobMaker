package com.jobmaker.work

import android.content.Context
import android.util.Log
import com.jobmaker.agents.Orchestrator
import com.jobmaker.agents.PipelineEvent
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.prefs.SettingsRepository
import com.jobmaker.data.repo.CandidatureRepository
import com.jobmaker.data.repo.ProfileRepository
import com.jobmaker.llm.LlmRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fait tourner le pipeline en dehors de tout ecran.
 *
 * Une generation dure des minutes. Tant qu'elle vivait dans le ViewModel de
 * l'ecran, quitter cet ecran -- ou simplement l'application -- l'annulait, et
 * il fallait rester devant le telephone a le regarder chauffer. Le travail est
 * donc detenu ici, par un objet de la duree de vie du processus, et un service
 * de premier plan est demarre le temps de la generation pour qu'Android ne
 * gele pas le processus quand l'application passe en arriere-plan.
 *
 * L'etat est un StateFlow : l'ecran s'y raccroche quand il revient, et
 * retrouve la generation exactement ou elle en est.
 */
class MoteurCandidature(
    private val appContext: Context,
    private val orchestrator: Orchestrator,
    private val llmRuntime: LlmRuntime,
    private val profileRepository: ProfileRepository,
    private val candidatureRepository: CandidatureRepository,
    private val settingsRepository: SettingsRepository,
) {
    // Volontairement independant de tout cycle de vie Android.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _etat = MutableStateFlow(EtatGeneration())
    val etat: StateFlow<EtatGeneration> = _etat.asStateFlow()

    private var travail: Job? = null

    val enCours: Boolean get() = _etat.value.enCours

    fun reinitialiser() {
        if (enCours) return
        _etat.value = EtatGeneration()
    }

    fun lancer(offre: String, candidatureExistante: Candidature? = null) {
        if (_etat.value.enCours) return
        travail?.cancel()
        _etat.value = EtatGeneration(
            enCours = true,
            etapeTitre = "Preparation...",
            debutMs = System.currentTimeMillis(),
        )
        ServiceGeneration.demarrer(appContext)

        travail = scope.launch {
            try {
                val profil = profileRepository.get()
                val reglages = settingsRepository.settings.first()
                orchestrator
                    .genererCandidature(offre, profil, reglages, candidatureExistante)
                    .collect { evenement -> appliquer(evenement) }
            } catch (t: CancellationException) {
                // annuler() a deja pose l'etat ; et dans une coroutine annulee
                // le moindre appel suspendu relancerait aussitot.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Generation interrompue par une erreur", t)
                runCatching { llmRuntime.unload() }
                _etat.value = _etat.value.copy(
                    enCours = false,
                    erreur = t.message ?: "Erreur inattendue pendant la generation.",
                )
            } finally {
                ServiceGeneration.arreter(appContext)
            }
        }
    }

    fun annuler() {
        _etat.value = _etat.value.copy(
            enCours = false,
            etapeTitre = "Generation interrompue",
            etapeDetail = "",
        )
        travail?.cancel()
        travail = null
        scope.launch { llmRuntime.unload() }
        ServiceGeneration.arreter(appContext)
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
                candidatureRepository.save(evenement.candidature)
                llmRuntime.unload()
                _etat.value = _etat.value.copy(
                    enCours = false,
                    etapeIndex = _etat.value.etapesTotal,
                    etapeTitre = "Termine",
                    etapeDetail = "",
                    candidatureId = evenement.candidature.id,
                )
            }

            is PipelineEvent.Echec -> {
                llmRuntime.unload()
                _etat.value = _etat.value.copy(enCours = false, erreur = evenement.message)
            }

            is PipelineEvent.ExplicationPrete -> Unit
        }
    }

    private companion object {
        const val TAG = "MoteurCandidature"
    }
}
