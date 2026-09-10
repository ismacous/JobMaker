package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.data.prefs.LangueSortie
import com.jobmaker.data.prefs.Settings
import com.jobmaker.di.AppContainer
import com.jobmaker.llm.AgentRole
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val reglages = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val installes = container.modelManager.installed

    val nombreCoeurs: Int = Runtime.getRuntime().availableProcessors()

    fun setModelePourRole(role: AgentRole, modelId: String) = lance {
        container.settingsRepository.setModelePourRole(role, modelId)
    }

    fun setModelePourTous(modelId: String) = lance {
        container.settingsRepository.setModelePourTousLesRoles(modelId)
    }

    fun setContexte(v: Int) = lance { container.settingsRepository.setTailleContexte(v) }
    fun setThreads(v: Int) = lance { container.settingsRepository.setThreads(v) }
    fun setCouchesGpu(v: Int) = lance { container.settingsRepository.setCouchesGpu(v) }
    fun setChargerEnMemoire(v: Boolean) =
        lance { container.settingsRepository.setChargerEnMemoire(v) }
    fun setGabarit(v: String) = lance { container.settingsRepository.setGabarit(v) }
    fun setCouleur(v: String) = lance { container.settingsRepository.setCouleurAccent(v) }
    fun setPhoto(v: Boolean) = lance { container.settingsRepository.setPhotoSurCv(v) }
    fun setRelecture(v: Boolean) = lance { container.settingsRepository.setRelectureActive(v) }
    fun setAnalyseApprofondie(v: Boolean) =
        lance { container.settingsRepository.setAnalyseApprofondie(v) }
    fun setCachePrompt(v: Boolean) = lance {
        container.settingsRepository.setCachePrompt(v)
        if (!v) container.modelManager.viderCaches()
    }

    /** Taille des caches de prompt, pour l'afficher dans les reglages. */
    fun tailleCaches(): Long = container.modelManager.tailleCaches()
    fun setPasses(v: Int) = lance { container.settingsRepository.setPassesCorrection(v) }
    fun setLangue(v: LangueSortie) = lance { container.settingsRepository.setLangueSortie(v) }
    fun setUnePage(v: Boolean) = lance { container.settingsRepository.setCvUnePage(v) }
    fun setOnboardingFait(v: Boolean) = lance { container.settingsRepository.setOnboardingFait(v) }

    private fun lance(bloc: suspend () -> Unit) {
        viewModelScope.launch { bloc() }
    }
}
