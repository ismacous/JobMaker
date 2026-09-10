package com.jobmaker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.LlmRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jobmaker_settings")

enum class LangueSortie(val label: String) {
    AUTO("Comme l'annonce"),
    FR("Toujours en francais"),
    EN("Toujours en anglais"),
}

data class Settings(
    /** Modele affecte a chaque agent. Vide = premier modele installe. */
    val modeleParRole: Map<AgentRole, String> = emptyMap(),
    val tailleContexte: Int = 6144,
    val threads: Int = LlmRuntime.defaultThreads(),
    val couchesGpu: Int = 0,
    /**
     * Copier les poids en memoire au lieu de les mapper depuis le fichier.
     * Ouverture plus lente, mais debit constant si Android evince les pages.
     */
    val chargerEnMemoire: Boolean = false,
    val gabaritParDefaut: String = "sobre",
    val couleurAccent: String = "#1F4E79",
    val photoSurCv: Boolean = false,
    /** Passe de relecture automatique + correction. Double le temps mais evite les betises. */
    /**
     * Relecture critique puis correction par le modele. Desactivee par defaut :
     * ce sont les deux etapes les plus cheres du pipeline -- elles relisent le
     * CV, la lettre, l'annonce et le profil, puis reecrivent tout -- et elles
     * doublaient a elles seules le temps de generation. Disponibles a la
     * demande sur une candidature deja produite.
     */
    val relectureActive: Boolean = false,
    /**
     * Etudier l'annonce dans un appel separe avant de rediger.
     *
     * Desactive par defaut. Le detail supplementaire est reel -- souhaits,
     * outils, attentes implicites, ecarts et reponses, experiences classees une
     * a une -- mais il se paie d'une seconde lecture entiere des consignes, de
     * l'annonce et du profil, et d'un millier de tokens ecrits pour le seul
     * usage de l'appel suivant. Mesure sur un S25 Ultra : cela double le temps.
     */
    val analyseApprofondie: Boolean = false,
    /**
     * Garder sur le disque l'etat interne des consignes et du profil.
     *
     * Un modele n'a aucune memoire d'un appel a l'autre : pour se servir d'un
     * texte il doit le convertir en etat interne, et c'est cette conversion qui
     * constitue la phase de lecture. Comme les consignes et le profil ne
     * changent pas d'une candidature a l'autre, cet etat est calcule une fois
     * puis relu -- une demi-seconde de disque au lieu de plusieurs minutes de
     * calcul. Le fichier pese quelques centaines de megaoctets.
     */
    val cachePrompt: Boolean = true,
    val passesCorrection: Int = 1,
    val langueSortie: LangueSortie = LangueSortie.AUTO,
    val cvUnePage: Boolean = true,
    val onboardingFait: Boolean = false,
) {
    fun modelePour(role: AgentRole): String? = modeleParRole[role]?.takeIf { it.isNotBlank() }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val roles = AgentRole.entries.mapNotNull { role ->
            this[roleKey(role)]?.takeIf { it.isNotBlank() }?.let { role to it }
        }.toMap()
        return Settings(
            modeleParRole = roles,
            tailleContexte = this[KEY_CONTEXTE] ?: 6144,
            threads = this[KEY_THREADS] ?: LlmRuntime.defaultThreads(),
            couchesGpu = this[KEY_GPU] ?: 0,
            chargerEnMemoire = this[KEY_EN_MEMOIRE] ?: false,
            gabaritParDefaut = this[KEY_GABARIT] ?: "sobre",
            couleurAccent = this[KEY_COULEUR] ?: "#1F4E79",
            photoSurCv = this[KEY_PHOTO] ?: false,
            relectureActive = this[KEY_RELECTURE] ?: false,
            analyseApprofondie = this[KEY_APPROFONDIE] ?: false,
            cachePrompt = this[KEY_CACHE_PROMPT] ?: true,
            passesCorrection = this[KEY_PASSES] ?: 1,
            langueSortie = runCatching {
                LangueSortie.valueOf(this[KEY_LANGUE] ?: LangueSortie.AUTO.name)
            }.getOrDefault(LangueSortie.AUTO),
            cvUnePage = this[KEY_UNE_PAGE] ?: true,
            onboardingFait = this[KEY_ONBOARDING] ?: false,
        )
    }

    suspend fun setModelePourRole(role: AgentRole, modelId: String) =
        context.dataStore.edit { it[roleKey(role)] = modelId }

    /** Affecte le meme modele a tous les roles : evite les rechargements. */
    suspend fun setModelePourTousLesRoles(modelId: String) = context.dataStore.edit { p ->
        AgentRole.entries.forEach { p[roleKey(it)] = modelId }
    }

    suspend fun setTailleContexte(value: Int) = context.dataStore.edit { it[KEY_CONTEXTE] = value }
    suspend fun setThreads(value: Int) = context.dataStore.edit { it[KEY_THREADS] = value }
    suspend fun setCouchesGpu(value: Int) = context.dataStore.edit { it[KEY_GPU] = value }
    suspend fun setChargerEnMemoire(value: Boolean) =
        context.dataStore.edit { it[KEY_EN_MEMOIRE] = value }
    suspend fun setGabarit(value: String) = context.dataStore.edit { it[KEY_GABARIT] = value }
    suspend fun setCouleurAccent(value: String) = context.dataStore.edit { it[KEY_COULEUR] = value }
    suspend fun setPhotoSurCv(value: Boolean) = context.dataStore.edit { it[KEY_PHOTO] = value }
    suspend fun setRelectureActive(value: Boolean) = context.dataStore.edit { it[KEY_RELECTURE] = value }
    suspend fun setAnalyseApprofondie(value: Boolean) =
        context.dataStore.edit { it[KEY_APPROFONDIE] = value }
    suspend fun setCachePrompt(value: Boolean) =
        context.dataStore.edit { it[KEY_CACHE_PROMPT] = value }
    suspend fun setPassesCorrection(value: Int) = context.dataStore.edit { it[KEY_PASSES] = value }
    suspend fun setLangueSortie(value: LangueSortie) =
        context.dataStore.edit { it[KEY_LANGUE] = value.name }
    suspend fun setCvUnePage(value: Boolean) = context.dataStore.edit { it[KEY_UNE_PAGE] = value }
    suspend fun setOnboardingFait(value: Boolean) = context.dataStore.edit { it[KEY_ONBOARDING] = value }

    private companion object {
        val KEY_CONTEXTE = intPreferencesKey("taille_contexte")
        val KEY_THREADS = intPreferencesKey("threads")
        val KEY_GPU = intPreferencesKey("couches_gpu")
        val KEY_EN_MEMOIRE = booleanPreferencesKey("charger_en_memoire")
        val KEY_GABARIT = stringPreferencesKey("gabarit")
        val KEY_COULEUR = stringPreferencesKey("couleur_accent")
        val KEY_PHOTO = booleanPreferencesKey("photo_cv")
        val KEY_RELECTURE = booleanPreferencesKey("relecture")
        val KEY_APPROFONDIE = booleanPreferencesKey("analyse_approfondie")
        val KEY_CACHE_PROMPT = booleanPreferencesKey("cache_prompt")
        val KEY_PASSES = intPreferencesKey("passes_correction")
        val KEY_LANGUE = stringPreferencesKey("langue_sortie")
        val KEY_UNE_PAGE = booleanPreferencesKey("cv_une_page")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_fait")

        fun roleKey(role: AgentRole) = stringPreferencesKey("modele_${role.name}")
    }
}
