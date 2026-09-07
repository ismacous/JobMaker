package com.jobmaker.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobmaker.data.model.Certification
import com.jobmaker.data.model.Competence
import com.jobmaker.data.model.ContexteRecherche
import com.jobmaker.data.model.Experience
import com.jobmaker.data.model.Formation
import com.jobmaker.data.model.GroupeCompetences
import com.jobmaker.data.model.Identite
import com.jobmaker.data.model.Langue
import com.jobmaker.data.model.Lien
import com.jobmaker.data.model.Profile
import com.jobmaker.data.model.Projet
import com.jobmaker.di.AppContainer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Ecran Profil.
 *
 * La saisie est enregistree automatiquement apres une seconde d'inactivite :
 * remplir un CV complet prend du temps, et perdre la saisie parce qu'on a
 * ferme l'application serait le pire defaut possible pour cet ecran.
 */
@OptIn(FlowPreview::class)
class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private val _enregistre = MutableStateFlow(true)
    val enregistre: StateFlow<Boolean> = _enregistre.asStateFlow()

    init {
        viewModelScope.launch {
            _profile.value = container.profileRepository.get()
            // drop(1) : on ne reenregistre pas la valeur qu'on vient de lire.
            _profile.drop(1).debounce(1000).collect { p ->
                container.profileRepository.save(p)
                _enregistre.value = true
            }
        }
    }

    private fun modifier(bloc: (Profile) -> Profile) {
        _enregistre.value = false
        _profile.value = bloc(_profile.value)
    }

    suspend fun sauvegarderMaintenant() {
        container.profileRepository.save(_profile.value)
        _enregistre.value = true
    }

    // --- identite ---
    fun majIdentite(bloc: (Identite) -> Identite) = modifier { it.copy(identite = bloc(it.identite)) }
    fun majPresentation(v: String) = modifier { it.copy(presentation = v) }
    fun majObjectif(v: String) = modifier { it.copy(objectifPro = v) }
    fun majRecherche(bloc: (ContexteRecherche) -> ContexteRecherche) =
        modifier { it.copy(recherche = bloc(it.recherche)) }

    // --- experiences ---
    fun ajouterExperience() = modifier { it.copy(experiences = it.experiences + Experience()) }
    fun majExperience(id: String, bloc: (Experience) -> Experience) = modifier { p ->
        p.copy(experiences = p.experiences.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerExperience(id: String) = modifier { p ->
        p.copy(experiences = p.experiences.filterNot { it.id == id })
    }
    fun deplacerExperience(id: String, delta: Int) = modifier { p ->
        p.copy(experiences = deplacer(p.experiences, { it.id == id }, delta))
    }

    // --- formations ---
    fun ajouterFormation() = modifier { it.copy(formations = it.formations + Formation()) }
    fun majFormation(id: String, bloc: (Formation) -> Formation) = modifier { p ->
        p.copy(formations = p.formations.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerFormation(id: String) = modifier { p ->
        p.copy(formations = p.formations.filterNot { it.id == id })
    }
    fun deplacerFormation(id: String, delta: Int) = modifier { p ->
        p.copy(formations = deplacer(p.formations, { it.id == id }, delta))
    }

    // --- competences ---
    fun ajouterGroupeCompetences(categorie: String) = modifier {
        it.copy(competences = it.competences + GroupeCompetences(categorie = categorie))
    }
    fun majGroupeCompetences(id: String, bloc: (GroupeCompetences) -> GroupeCompetences) = modifier { p ->
        p.copy(competences = p.competences.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerGroupeCompetences(id: String) = modifier { p ->
        p.copy(competences = p.competences.filterNot { it.id == id })
    }
    fun ajouterCompetence(groupeId: String, nom: String) = majGroupeCompetences(groupeId) { g ->
        if (nom.isBlank()) g else g.copy(items = g.items + Competence(nom = nom.trim()))
    }
    fun supprimerCompetence(groupeId: String, index: Int) = majGroupeCompetences(groupeId) { g ->
        g.copy(items = g.items.filterIndexed { i, _ -> i != index })
    }
    fun majNiveauCompetence(groupeId: String, index: Int, niveau: Int) =
        majGroupeCompetences(groupeId) { g ->
            g.copy(items = g.items.mapIndexed { i, c -> if (i == index) c.copy(niveau = niveau) else c })
        }

    // --- langues ---
    fun ajouterLangue() = modifier { it.copy(langues = it.langues + Langue()) }
    fun majLangue(id: String, bloc: (Langue) -> Langue) = modifier { p ->
        p.copy(langues = p.langues.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerLangue(id: String) = modifier { p ->
        p.copy(langues = p.langues.filterNot { it.id == id })
    }

    // --- certifications / projets / liens ---
    fun ajouterCertification() = modifier { it.copy(certifications = it.certifications + Certification()) }
    fun majCertification(id: String, bloc: (Certification) -> Certification) = modifier { p ->
        p.copy(certifications = p.certifications.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerCertification(id: String) = modifier { p ->
        p.copy(certifications = p.certifications.filterNot { it.id == id })
    }

    fun ajouterProjet() = modifier { it.copy(projets = it.projets + Projet()) }
    fun majProjet(id: String, bloc: (Projet) -> Projet) = modifier { p ->
        p.copy(projets = p.projets.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerProjet(id: String) = modifier { p ->
        p.copy(projets = p.projets.filterNot { it.id == id })
    }

    fun ajouterLien() = modifier { it.copy(liens = it.liens + Lien()) }
    fun majLien(id: String, bloc: (Lien) -> Lien) = modifier { p ->
        p.copy(liens = p.liens.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerLien(id: String) = modifier { p ->
        p.copy(liens = p.liens.filterNot { it.id == id })
    }

    fun ajouterBenevolat() = modifier { it.copy(benevolat = it.benevolat + Experience()) }
    fun majBenevolat(id: String, bloc: (Experience) -> Experience) = modifier { p ->
        p.copy(benevolat = p.benevolat.map { if (it.id == id) bloc(it) else it })
    }
    fun supprimerBenevolat(id: String) = modifier { p ->
        p.copy(benevolat = p.benevolat.filterNot { it.id == id })
    }

    fun majPermis(v: List<String>) = modifier { it.copy(permis = v) }
    fun majCentresInteret(v: List<String>) = modifier { it.copy(centresInteret = v) }

    // --- sauvegarde / restauration ---
    suspend fun exporterJson(): String = container.profileRepository.exportJson()

    suspend fun importerJson(texte: String): Result<Unit> =
        container.profileRepository.importJson(texte).map {
            _profile.value = it
            _enregistre.value = true
        }

    private fun <T> deplacer(liste: List<T>, predicat: (T) -> Boolean, delta: Int): List<T> {
        val index = liste.indexOfFirst(predicat)
        if (index < 0) return liste
        val cible = (index + delta).coerceIn(0, liste.size - 1)
        if (cible == index) return liste
        val copie = liste.toMutableList()
        copie.add(cible, copie.removeAt(index))
        return copie
    }
}
