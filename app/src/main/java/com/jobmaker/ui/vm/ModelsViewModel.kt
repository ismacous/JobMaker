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
                    chargerEnMemoire = reglagesActuels.chargerEnMemoire,
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

    private fun ligneMesure(m: com.jobmaker.llm.LlmRuntime.Mesure): String =
        "%d thread%s : lecture %.1f tok/s | ecriture %.2f tok/s".format(
            m.nThreads, if (m.nThreads > 1) "s" else "",
            m.lectureParSeconde, m.ecritureParSeconde,
        )

    private fun ligneLongueur(m: com.jobmaker.llm.LlmRuntime.Mesure): String =
        "%5d tokens (lots de %3d) : lecture %6.1f tok/s | ecriture %5.2f tok/s".format(
            m.tokensLus, m.nLot, m.lectureParSeconde, m.ecritureParSeconde,
        )

    private fun ligneCompteurs(m: com.jobmaker.llm.LlmRuntime.Mesure): String =
        "   coeurs occupes %.2f | defauts majeurs %d | lu sur stockage %s".format(
            m.coeursOccupes,
            m.compteurs.defautsMajeurs,
            if (m.compteurs.octetsLus < 0) "non mesurable"
            else "%.0f Mo".format(m.compteurs.octetsLus / 1e6),
        )

    /**
     * Mesure sur l'appareil ce que fait reellement le moteur.
     *
     * Chronometre separement la lecture d'un prompt et l'ecriture de tokens, a
     * plusieurs nombres de threads, en relevant a chaque fois le temps
     * processeur consomme, les defauts de page majeurs et les octets lus sur le
     * stockage. Le resultat dit lequel des deux problemes on a : le telephone
     * calcule trop lentement, ou il passe son temps a relire le modele.
     */
    fun diagnostiquer(modelId: String) {
        if (_testEnCours.value) return
        _testEnCours.value = true
        _resultatTest.value = "Mesure en cours. Comptez quelques minutes : six passages, " +
            "dont un a froid. Restez sur cet ecran."
        viewModelScope.launch {
            runCatching {
                val fichier = container.modelManager.installedFile(modelId)
                    ?: error("Fichier introuvable.")
                val entree = container.modelManager.catalog.byId(modelId)
                val r = reglages.value
                val coeurs = Runtime.getRuntime().availableProcessors()

                val debutChargement = System.currentTimeMillis()
                val info = container.llmRuntime.ensureLoaded(
                    modelId = modelId,
                    filePath = fichier.absolutePath,
                    contextSize = minOf(
                        r.tailleContexte,
                        entree?.contextMax ?: r.tailleContexte,
                    ),
                    threads = r.threads,
                    gpuLayers = r.couchesGpu,
                    chargerEnMemoire = r.chargerEnMemoire,
                )
                val msChargement = System.currentTimeMillis() - debutChargement
                val infoMoteur = container.llmRuntime.systemInfo()

                // Volontairement court : aux vitesses constatees, 128 tokens par
                // passe demanderaient une heure de mesure.
                val tokensPrompt = 32
                val tokensEcrits = 4

                // Premier passage : il paie le chargement des pages du modele
                // depuis le stockage. L'ecart avec les suivants mesure
                // exactement le cout du mmap.
                val chauffe = container.llmRuntime.mesurer(
                    nPrompt = tokensPrompt, nGen = tokensEcrits, nThreads = r.threads,
                )

                // De 1 a tous les coeurs : si la vitesse ne monte pas avec les
                // threads, le probleme n'est pas la puissance de calcul.
                val essais = listOf(1, 2, 4, 6, 8).filter { it <= coeurs }.distinct()
                val mesures = essais.map { n ->
                    container.llmRuntime.mesurer(
                        nPrompt = tokensPrompt, nGen = tokensEcrits, nThreads = n,
                    )
                }

                // Le banc precedent lisait 32 tokens d'un seul lot et trouvait
                // le moteur rapide, alors qu'une vraie generation en lit un
                // millier par lots de 256 et rampe. L'ecart tient forcement a
                // l'une de ces deux differences : ce balayage les separe.
                val longueurs = listOf(
                    32 to 32,
                    128 to 128,
                    256 to 256,
                    512 to 256,
                    1024 to 256,
                    1024 to 64,
                    2048 to 256,
                ).filter { (n, _) -> n + tokensEcrits + 8 <= info.contextSize }
                // Du plus court au plus long, avec un budget : si l'effondrement
                // est bien la, les passages longs prendraient vingt minutes et
                // le banc deviendrait inutilisable.
                val parLongueur = mutableListOf<com.jobmaker.llm.LlmRuntime.Mesure>()
                var budgetMs = 120_000L
                var abandonne = false
                for ((n, lot) in longueurs) {
                    if (budgetMs <= 0) { abandonne = true; break }
                    val m = container.llmRuntime.mesurer(
                        nPrompt = n, nGen = tokensEcrits, nThreads = r.threads, nLot = lot,
                    )
                    parLongueur += m
                    budgetMs -= m.msLecture + m.msEcriture
                }

                buildString {
                    appendLine("MODELE")
                    appendLine(info.description)
                    appendLine("Fichier : %.2f Go".format(fichier.length() / 1e9))
                    appendLine(
                        "Poids : " +
                            (if (info.enMemoire) "copies en memoire"
                            else "mappes depuis le fichier")
                    )
                    appendLine("Ouverture : ${msChargement / 1000.0} s")
                    appendLine()
                    appendLine("APPAREIL")
                    appendLine("Coeurs vus par l'application : $coeurs")
                    appendLine("Contexte : ${info.contextSize} tokens")
                    appendLine()
                    appendLine("PREMIER PASSAGE (pages du modele encore a lire)")
                    appendLine(ligneMesure(chauffe))
                    appendLine(ligneCompteurs(chauffe))
                    appendLine()
                    appendLine("MESURES A CHAUD ($tokensPrompt tokens lus, $tokensEcrits ecrits)")
                    mesures.forEach { m ->
                        appendLine(ligneMesure(m))
                        appendLine(ligneCompteurs(m))
                    }
                    appendLine()
                    appendLine("LONGUEUR DU PROMPT (a ${r.threads} threads)")
                    appendLine("Une vraie generation lit environ 1400 tokens par lots de 256.")
                    parLongueur.forEach { appendLine(ligneLongueur(it)) }
                    if (abandonne) {
                        appendLine("(arrete ici : deux minutes de mesure deja depensees)")
                    }
                    appendLine()
                    appendLine("LECTURE DU RESULTAT")
                    val meilleure = mesures.maxByOrNull { it.ecritureParSeconde }
                    if (meilleure != null) {
                        appendLine(
                            if (meilleure.coeursOccupes < 1.0)
                                "Les coeurs occupes sont sous 1 : le moteur attend la memoire " +
                                    "ou le stockage, il ne calcule pas."
                            else if (meilleure.coeursOccupes < meilleure.nThreads * 0.6)
                                "Les threads ne travaillent qu'a temps partiel : contention " +
                                    "ou attente memoire."
                            else
                                "Les coeurs sont pleinement occupes : la lenteur vient bien du " +
                                    "calcul, pas d'une attente."
                        )
                        appendLine("Meilleur reglage mesure : ${meilleure.nThreads} threads.")
                    }
                    val court = parLongueur.firstOrNull()
                    val long = parLongueur.lastOrNull()
                    if (court != null && long != null && court !== long &&
                        long.ecritureParSeconde > 0.0
                    ) {
                        val chute = court.ecritureParSeconde / long.ecritureParSeconde
                        appendLine(
                            "Passer de %d a %d tokens de contexte divise l'ecriture par %.1f."
                                .format(court.tokensLus, long.tokensLus, chute)
                        )
                    }
                    appendLine()
                    appendLine("MOTEUR")
                    append(infoMoteur)
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
