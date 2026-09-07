package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.agents.FactCheck
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Profile
import com.jobmaker.data.model.StatutCandidature
import com.jobmaker.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface EvenementExport {
    data class Pret(val fichier: File, val typeMime: String, val sujet: String) : EvenementExport
    data class Erreur(val message: String) : EvenementExport
    data object EnCours : EvenementExport
}

/** Liste des candidatures, edition et export. */
class DocumentsViewModel(private val container: AppContainer) : ViewModel() {

    val candidatures = container.candidatureRepository.all
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val profile: StateFlow<Profile> = container.profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, Profile())

    private val _courante = MutableStateFlow<Candidature?>(null)
    val courante: StateFlow<Candidature?> = _courante.asStateFlow()

    private val _export = MutableStateFlow<EvenementExport?>(null)
    val export: StateFlow<EvenementExport?> = _export.asStateFlow()

    fun charger(id: String) {
        viewModelScope.launch { _courante.value = container.candidatureRepository.get(id) }
    }

    fun supprimer(id: String) {
        viewModelScope.launch {
            container.candidatureRepository.delete(id)
            if (_courante.value?.id == id) _courante.value = null
        }
    }

    fun dupliquer(id: String, onFait: (String) -> Unit) {
        viewModelScope.launch {
            val source = container.candidatureRepository.get(id) ?: return@launch
            val copie = source.copy(
                id = java.util.UUID.randomUUID().toString(),
                statut = StatutCandidature.BROUILLON,
                creeLe = System.currentTimeMillis(),
                modifieLe = System.currentTimeMillis(),
            )
            container.candidatureRepository.save(copie)
            onFait(copie.id)
        }
    }

    /**
     * Change le statut d'une candidature identifiee par son id.
     *
     * Depuis la liste, on ne peut pas passer par [charger] puis [majStatut] :
     * le chargement est asynchrone et l'ecriture partirait sur un dossier
     * encore vide.
     */
    fun majStatutDe(id: String, statut: StatutCandidature) {
        viewModelScope.launch {
            val existante = container.candidatureRepository.get(id) ?: return@launch
            val misAJour = existante.copy(statut = statut)
            container.candidatureRepository.save(misAJour)
            if (_courante.value?.id == id) _courante.value = misAJour
        }
    }

    // -----------------------------------------------------------------------
    // Edition
    // -----------------------------------------------------------------------

    private fun modifier(bloc: (Candidature) -> Candidature) {
        val actuelle = _courante.value ?: return
        val misAJour = bloc(actuelle)
        _courante.value = misAJour
        viewModelScope.launch { container.candidatureRepository.save(misAJour) }
    }

    fun majCv(bloc: (CvContent) -> CvContent) = modifier { candidature ->
        val cv = bloc(candidature.cv)
        // Le score de couverture se recalcule a chaque modification manuelle :
        // c'est le retour dont on a besoin quand on retouche le texte a la main.
        val rapport = FactCheck.verifier(profile.value, candidature.analyse, cv, candidature.lettre)
        candidature.copy(cv = cv, scoreAts = rapport.scoreAts)
    }

    fun majLettre(bloc: (LetterContent) -> LetterContent) =
        modifier { it.copy(lettre = bloc(it.lettre)) }

    fun majGabarit(id: String) = modifier { it.copy(gabarit = id) }
    fun majCouleur(hex: String) = modifier { it.copy(couleurAccent = hex) }
    fun majPhoto(actif: Boolean) = modifier { it.copy(avecPhoto = actif) }
    fun majStatut(statut: StatutCandidature) = modifier { it.copy(statut = statut) }
    fun majNotes(notes: String) = modifier { it.copy(notesPerso = notes) }

    // -----------------------------------------------------------------------
    // Apercu et export
    // -----------------------------------------------------------------------

    fun htmlCv(): String {
        val c = _courante.value ?: return "<html><body></body></html>"
        return container.documentExporter.htmlCv(profile.value, c)
    }

    fun htmlLettre(): String {
        val c = _courante.value ?: return "<html><body></body></html>"
        return container.documentExporter.htmlLettre(profile.value, c)
    }

    fun consommerExport() { _export.value = null }

    fun exporterCvPdf() = exporter("application/pdf") { p, c ->
        container.documentExporter.exporterCvPdf(p, c)
    }

    fun exporterLettrePdf() = exporter("application/pdf") { p, c ->
        container.documentExporter.exporterLettrePdf(p, c)
    }

    fun exporterCvTexte() = exporter("text/plain") { p, c ->
        container.documentExporter.exporterCvTexte(p, c)
    }

    fun exporterLettreTexte() = exporter("text/plain") { p, c ->
        container.documentExporter.exporterLettreTexte(p, c)
    }

    fun texteCvPourCopie(): String {
        val c = _courante.value ?: return ""
        return container.documentExporter.texteCv(profile.value, c)
    }

    fun texteLettrePourCopie(): String {
        val c = _courante.value ?: return ""
        return container.documentExporter.texteLettre(profile.value, c.lettre)
    }

    fun uriPartage(fichier: File) = container.documentExporter.uriPartage(fichier)

    fun intentPartage(fichier: File, typeMime: String, sujet: String) =
        container.documentExporter.intentPartage(fichier, typeMime, sujet)

    fun intentOuvrir(fichier: File, typeMime: String) =
        container.documentExporter.intentOuvrir(fichier, typeMime)

    private fun exporter(
        typeMime: String,
        bloc: suspend (Profile, Candidature) -> File,
    ) {
        val candidature = _courante.value ?: return
        _export.value = EvenementExport.EnCours
        viewModelScope.launch {
            runCatching { bloc(profile.value, candidature) }
                .onSuccess {
                    _export.value = EvenementExport.Pret(
                        it, typeMime,
                        "Candidature ${candidature.analyse.poste}",
                    )
                }
                .onFailure {
                    _export.value = EvenementExport.Erreur(it.message ?: "Export impossible")
                }
        }
    }
}
