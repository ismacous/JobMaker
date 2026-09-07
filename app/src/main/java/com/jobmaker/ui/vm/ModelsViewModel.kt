package com.jobmaker.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.di.AppContainer
import com.jobmaker.llm.CatalogModel
import com.jobmaker.llm.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ecran de gestion des modeles : telechargement, import, suppression, test. */
class ModelsViewModel(private val container: AppContainer) : ViewModel() {

    val catalogue: List<CatalogModel> = container.modelManager.catalog.models
    val installes = container.modelManager.installed
    val telechargements = container.modelManager.downloads

    val reglages = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.jobmaker.data.prefs.Settings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _testEnCours = MutableStateFlow(false)
    val testEnCours: StateFlow<Boolean> = _testEnCours.asStateFlow()

    private val _resultatTest = MutableStateFlow<String?>(null)
    val resultatTest: StateFlow<String?> = _resultatTest.asStateFlow()

    private val travaux = mutableMapOf<String, Job>()

    val espaceLibre: Long get() = container.modelManager.freeSpaceBytes()

    val moteurNatifDisponible: Boolean get() = container.llmRuntime.nativeAvailable

    fun consommerMessage() { _message.value = null }
    fun effacerTest() { _resultatTest.value = null }

    fun telecharger(model: CatalogModel) {
        if (travaux[model.id]?.isActive == true) return
        travaux[model.id] = viewModelScope.launch(Dispatchers.IO) {
            container.modelManager.download(model)
            // Premier modele installe : on l'affecte a tous les roles pour que
            // l'application soit immediatement utilisable.
            val installesApres = container.modelManager.installed.value
            if (installesApres.size == 1) {
                container.settingsRepository.setModelePourTousLesRoles(installesApres.first().id)
            }
        }
    }

    /**
     * Telecharge un GGUF depuis un lien colle. Recours quand un depot du
     * catalogue a change de nom : on retrouve le fichier depuis le navigateur
     * du telephone et on colle son lien.
     */
    fun telechargerDepuisLien(url: String) {
        val id = container.modelManager.idDepuisUrl(url)
        if (travaux[id]?.isActive == true) return
        travaux[id] = viewModelScope.launch(Dispatchers.IO) {
            container.modelManager.downloadFromUrl(url)
            val installesApres = container.modelManager.installed.value
            if (installesApres.size == 1) {
                container.settingsRepository.setModelePourTousLesRoles(installesApres.first().id)
            }
        }
    }

    /** Cle de suivi de l'avancement pour un lien donne. */
    fun idDepuisLien(url: String): String = container.modelManager.idDepuisUrl(url)

    fun annuler(modelId: String) {
        container.modelManager.cancelDownload(modelId)
        travaux[modelId]?.cancel()
    }

    fun supprimer(modelId: String) {
        viewModelScope.launch {
            container.llmRuntime.unload()
            container.modelManager.delete(modelId)
            _message.value = "Modele supprime."
        }
    }

    fun importer(uri: Uri, nom: String) {
        viewModelScope.launch {
            container.modelManager.importFromUri(uri, nom)
                .onSuccess { _message.value = "Modele importe : ${it.displayName}" }
                .onFailure { _message.value = it.message ?: "Import impossible" }
        }
    }

    fun definirPourTousLesRoles(modelId: String) {
        viewModelScope.launch {
            container.settingsRepository.setModelePourTousLesRoles(modelId)
            _message.value = "Modele utilise pour toutes les etapes."
        }
    }

    /**
     * Charge un modele et lui fait ecrire une phrase. Sert a verifier que la
     * chaine complete fonctionne (fichier lisible, memoire suffisante, moteur
     * natif present) sans lancer une generation de dix minutes.
     */
    fun tester(modelId: String) {
        if (_testEnCours.value) return
        _testEnCours.value = true
        _resultatTest.value = null
        viewModelScope.launch {
            val debut = System.currentTimeMillis()
            runCatching {
                val fichier = container.modelManager.installedFile(modelId)
                    ?: error("Fichier introuvable.")
                val entree = container.modelManager.catalog.byId(modelId)
                val reglagesActuels = reglages.value
                container.llmRuntime.ensureLoaded(
                    modelId = modelId,
                    filePath = fichier.absolutePath,
                    contextSize = minOf(
                        reglagesActuels.tailleContexte,
                        entree?.contextMax ?: reglagesActuels.tailleContexte,
                    ),
                    threads = reglagesActuels.threads,
                    gpuLayers = reglagesActuels.couchesGpu,
                )
                val charge = System.currentTimeMillis()
                val reponse = container.llmRuntime.complete(
                    messages = listOf(
                        com.jobmaker.llm.ChatMessage.system(
                            "Tu reponds en francais, en une seule phrase courte."
                        ),
                        com.jobmaker.llm.ChatMessage.user(
                            "Ecris une phrase d'accroche de CV pour un magasinier."
                        ),
                    ),
                    params = com.jobmaker.llm.GenerationParams(maxTokens = 60, temperature = 0.5f),
                )
                val fin = System.currentTimeMillis()
                val texte = com.jobmaker.agents.JsonRepair.cleanProse(reponse)
                val tokens = container.llmRuntime.estimateTokens(texte).coerceAtLeast(1)
                val vitesse = tokens * 1000.0 / (fin - charge).coerceAtLeast(1)
                buildString {
                    appendLine("Chargement : ${(charge - debut) / 1000.0} s")
                    appendLine("Generation : ${(fin - charge) / 1000.0} s (~%.1f tokens/s)".format(vitesse))
                    appendLine()
                    appendLine("Reponse du modele :")
                    append(texte.ifBlank { "(vide)" })
                }
            }.onSuccess { _resultatTest.value = it }
                .onFailure { _resultatTest.value = "Echec : ${it.message}" }
            container.llmRuntime.unload()
            _testEnCours.value = false
        }
    }

    suspend fun infoMoteur(): String = container.llmRuntime.systemInfo()

    companion object {
        fun libelleTier(tier: ModelTier): String = when (tier) {
            ModelTier.RECOMMENDED -> "Recommande"
            ModelTier.FAST -> "Rapide"
            ModelTier.MAX_QUALITY -> "Qualite max"
            ModelTier.HEAVY -> "Tres lourd"
        }
    }
}
