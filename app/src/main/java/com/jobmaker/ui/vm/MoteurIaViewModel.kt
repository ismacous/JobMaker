package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.data.prefs.Settings
import com.jobmaker.di.AppContainer
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.ModeMoteur
import com.jobmaker.llm.cloud.FournisseurCloud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Resultat du bouton "Tester la connexion". */
sealed interface EtatTest {
    data object Repos : EtatTest
    data object EnCours : EtatTest
    data class Reussi(val dureeMs: Long, val modele: String) : EtatTest
    data class Echoue(val message: String) : EtatTest
}

data class EtatMoteurIa(
    /**
     * Cle en cours de saisie. Videe des qu'elle part au coffre : elle n'a
     * aucune raison de survivre dans la memoire d'un ViewModel.
     */
    val cleSaisie: String = "",
    val modeles: List<String> = emptyList(),
    val chargementModeles: Boolean = false,
    val message: String? = null,
    val erreur: String? = null,
    val test: EtatTest = EtatTest.Repos,
)

/** Ecran "Moteur d'IA" : choix du mode, du fournisseur, de la cle et du modele. */
class MoteurIaViewModel(private val container: AppContainer) : ViewModel() {

    val reglages: StateFlow<Settings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Empreintes masquees des cles enregistrees, jamais les cles elles-memes. */
    val empreintes = container.coffreCles.empreintes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val modelesLocaux = container.modelManager.installed

    private val _etat = MutableStateFlow(EtatMoteurIa())
    val etat: StateFlow<EtatMoteurIa> = _etat.asStateFlow()

    // -----------------------------------------------------------------------
    // Reglages
    // -----------------------------------------------------------------------

    fun setMode(mode: ModeMoteur) = lance {
        container.settingsRepository.setModeMoteur(mode)
    }

    fun setFournisseur(f: FournisseurCloud) = lance {
        container.settingsRepository.setFournisseurCloud(f)
        // La liste de modeles affichee appartient au fournisseur precedent.
        _etat.value = _etat.value.copy(modeles = emptyList(), test = EtatTest.Repos)
    }

    fun setModele(modele: String) = lance {
        container.settingsRepository.setModeleCloud(reglages.value.fournisseurCloud, modele)
    }

    fun setRepliLocal(actif: Boolean) = lance {
        container.settingsRepository.setRepliLocal(actif)
    }

    // -----------------------------------------------------------------------
    // Cle
    // -----------------------------------------------------------------------

    fun majCleSaisie(valeur: String) {
        _etat.value = _etat.value.copy(cleSaisie = valeur, message = null, erreur = null)
    }

    fun enregistrerCle() = lance {
        val fournisseur = reglages.value.fournisseurCloud
        val saisie = _etat.value.cleSaisie.trim()
        if (!fournisseur.formatPlausible(saisie)) {
            _etat.value = _etat.value.copy(
                erreur = "Cette cle ne ressemble pas a une cle ${fournisseur.nom}. " +
                    "Collez-la en entier, sans espace ni guillemet.",
            )
            return@lance
        }
        container.coffreCles.enregistrer(fournisseur, saisie)
        _etat.value = _etat.value.copy(
            cleSaisie = "",
            message = "Cle enregistree et chiffree sur l'appareil.",
            erreur = null,
        )
        rafraichirModeles()
    }

    fun effacerCle() = lance {
        container.coffreCles.effacer(reglages.value.fournisseurCloud)
        _etat.value = _etat.value.copy(
            modeles = emptyList(),
            message = "Cle supprimee de l'appareil.",
            test = EtatTest.Repos,
        )
    }

    // -----------------------------------------------------------------------
    // Modeles et test
    // -----------------------------------------------------------------------

    /**
     * Demande au fournisseur ce qu'il sert aujourd'hui.
     *
     * Les catalogues changent plusieurs fois par an et les modeles retires
     * repondent 404 : une liste figee dans l'application serait fausse avant la
     * prochaine mise a jour.
     */
    fun rafraichirModeles() = lance {
        val fournisseur = reglages.value.fournisseurCloud
        val cle = container.coffreCles.cle(fournisseur)
        if (cle.isNullOrBlank()) {
            _etat.value = _etat.value.copy(
                erreur = "Enregistrez d'abord une cle ${fournisseur.nom}.",
            )
            return@lance
        }
        _etat.value = _etat.value.copy(chargementModeles = true, erreur = null)
        runCatching { container.clientCloud.listerModeles(fournisseur, cle) }
            .onSuccess { liste ->
                _etat.value = _etat.value.copy(
                    modeles = liste,
                    chargementModeles = false,
                    message = "${liste.size} modeles disponibles avec cette cle.",
                )
            }
            .onFailure { e ->
                _etat.value = _etat.value.copy(
                    chargementModeles = false,
                    erreur = e.message ?: "Liste des modeles indisponible.",
                )
            }
    }

    /** Un aller-retour reel, pour verifier cle, modele et reseau d'un coup. */
    fun tester() = lance {
        val settings = container.settingsRepository.settings.first()
        val fournisseur = settings.fournisseurCloud
        val modele = settings.modeleCloudPour(fournisseur)
        val cle = container.coffreCles.cle(fournisseur)
        if (cle.isNullOrBlank()) {
            _etat.value = _etat.value.copy(
                test = EtatTest.Echoue("Aucune cle enregistree pour ${fournisseur.nom}."),
            )
            return@lance
        }
        _etat.value = _etat.value.copy(test = EtatTest.EnCours)
        val debut = System.currentTimeMillis()
        runCatching {
            container.clientCloud.completer(
                fournisseur = fournisseur,
                modele = modele,
                cle = cle,
                messages = listOf(
                    ChatMessage.system("Tu reponds en un mot."),
                    ChatMessage.user("Reponds exactement : pret"),
                ),
                params = GenerationParams.precise(maxTokens = 16),
            )
        }
            .onSuccess {
                _etat.value = _etat.value.copy(
                    test = EtatTest.Reussi(System.currentTimeMillis() - debut, modele),
                )
            }
            .onFailure { e ->
                _etat.value = _etat.value.copy(
                    test = EtatTest.Echoue(e.message ?: "Echec de l'appel."),
                )
            }
    }

    private fun lance(bloc: suspend () -> Unit) {
        viewModelScope.launch { bloc() }
    }
}
