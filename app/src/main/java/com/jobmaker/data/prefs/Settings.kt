package com.jobmaker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.ConfigMoteur
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.ModeMoteur
import com.jobmaker.llm.cloud.FournisseurCloud
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jobmaker_settings")

enum class LangueSortie(val label: String) {
    AUTO("Comme l'annonce"),
    FR("Toujours en francais"),
    EN("Toujours en anglais"),
}

data class Settings(
    /**
     * Ou tourne le calcul. Par defaut l'API gratuite : une candidature y prend
     * quelques secondes contre une dizaine de minutes sur le telephone, pour un
     * meilleur resultat. Sans cle enregistree, l'application retombe d'elle-meme
     * sur le modele local en le disant.
     */
    val modeMoteur: ModeMoteur = ModeMoteur.CLOUD,
    val fournisseurCloud: FournisseurCloud = FournisseurCloud.GROQ,
    /** Modele choisi chez chaque fournisseur. Absent = modele par defaut. */
    val modeleCloud: Map<FournisseurCloud, String> = emptyMap(),
    /**
     * Modele plus petit reserve a l'etape de preparation, par fournisseur.
     * Vide = le meme modele aux trois etapes.
     */
    val modeleCloudLeger: Map<FournisseurCloud, String> = emptyMap(),
    /**
     * Passe au fournisseur suivant quand celui en cours sature, au lieu de
     * rendre la main au telephone pendant qu'une autre cle dort.
     */
    val enchainerFournisseurs: Boolean = true,
    /** Repli sur le modele du telephone quand plus aucun fournisseur ne repond. */
    val repliLocal: Boolean = true,
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
     * Relecture en mode API. Activee par defaut, contrairement au mode local :
     * elle n'y coute que quelques secondes de calcul. Reste debrayable, car sur
     * un quota gratuit serre chaque etape supplementaire peut imposer d'attendre
     * le renouvellement de la fenetre de tokens.
     */
    val relectureCloud: Boolean = true,
    val passesCorrection: Int = 1,
    val langueSortie: LangueSortie = LangueSortie.AUTO,
    val cvUnePage: Boolean = true,
    val onboardingFait: Boolean = false,
) {
    fun modelePour(role: AgentRole): String? = modeleParRole[role]?.takeIf { it.isNotBlank() }

    fun modeleCloudPour(f: FournisseurCloud): String =
        modeleCloud[f]?.takeIf { it.isNotBlank() } ?: f.modeleParDefaut

    fun modeleLegerPour(f: FournisseurCloud): String =
        modeleCloudLeger[f]?.takeIf { it.isNotBlank() }.orEmpty()
}

/** Projette les reglages sur ce dont la fabrique de moteurs a besoin. */
fun Settings.configMoteur() = ConfigMoteur(
    mode = modeMoteur,
    fournisseur = fournisseurCloud,
    modelesCloud = modeleCloud,
    modelesLegers = modeleCloudLeger,
    enchainerFournisseurs = enchainerFournisseurs,
    repliLocal = repliLocal,
    modeleParRole = modeleParRole,
    tailleContexte = tailleContexte,
    threads = threads,
    couchesGpu = couchesGpu,
    chargerEnMemoire = chargerEnMemoire,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val roles = AgentRole.entries.mapNotNull { role ->
            this[roleKey(role)]?.takeIf { it.isNotBlank() }?.let { role to it }
        }.toMap()
        val modelesCloud = FournisseurCloud.entries.mapNotNull { f ->
            this[modeleCloudKey(f)]?.takeIf { it.isNotBlank() }?.let { f to it }
        }.toMap()
        val modelesLegers = FournisseurCloud.entries.mapNotNull { f ->
            this[modeleLegerKey(f)]?.takeIf { it.isNotBlank() }?.let { f to it }
        }.toMap()
        return Settings(
            modeMoteur = runCatching {
                ModeMoteur.valueOf(this[KEY_MODE_MOTEUR] ?: ModeMoteur.CLOUD.name)
            }.getOrDefault(ModeMoteur.CLOUD),
            fournisseurCloud = FournisseurCloud.parNom(this[KEY_FOURNISSEUR]),
            modeleCloud = modelesCloud,
            modeleCloudLeger = modelesLegers,
            enchainerFournisseurs = this[KEY_ENCHAINER] ?: true,
            repliLocal = this[KEY_REPLI_LOCAL] ?: true,
            modeleParRole = roles,
            tailleContexte = this[KEY_CONTEXTE] ?: 6144,
            threads = this[KEY_THREADS] ?: LlmRuntime.defaultThreads(),
            couchesGpu = this[KEY_GPU] ?: 0,
            chargerEnMemoire = this[KEY_EN_MEMOIRE] ?: false,
            gabaritParDefaut = this[KEY_GABARIT] ?: "sobre",
            couleurAccent = this[KEY_COULEUR] ?: "#1F4E79",
            photoSurCv = this[KEY_PHOTO] ?: false,
            relectureActive = this[KEY_RELECTURE] ?: false,
            relectureCloud = this[KEY_RELECTURE_CLOUD] ?: true,
            passesCorrection = this[KEY_PASSES] ?: 1,
            langueSortie = runCatching {
                LangueSortie.valueOf(this[KEY_LANGUE] ?: LangueSortie.AUTO.name)
            }.getOrDefault(LangueSortie.AUTO),
            cvUnePage = this[KEY_UNE_PAGE] ?: true,
            onboardingFait = this[KEY_ONBOARDING] ?: false,
        )
    }

    suspend fun setModeMoteur(value: ModeMoteur) =
        context.dataStore.edit { it[KEY_MODE_MOTEUR] = value.name }

    suspend fun setFournisseurCloud(value: FournisseurCloud) =
        context.dataStore.edit { it[KEY_FOURNISSEUR] = value.name }

    suspend fun setModeleCloud(fournisseur: FournisseurCloud, modele: String) =
        context.dataStore.edit { it[modeleCloudKey(fournisseur)] = modele.trim() }

    suspend fun setModeleLeger(fournisseur: FournisseurCloud, modele: String) =
        context.dataStore.edit { it[modeleLegerKey(fournisseur)] = modele.trim() }

    suspend fun setRepliLocal(value: Boolean) =
        context.dataStore.edit { it[KEY_REPLI_LOCAL] = value }

    suspend fun setEnchainerFournisseurs(value: Boolean) =
        context.dataStore.edit { it[KEY_ENCHAINER] = value }

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
    suspend fun setRelectureCloud(value: Boolean) =
        context.dataStore.edit { it[KEY_RELECTURE_CLOUD] = value }
    suspend fun setPassesCorrection(value: Int) = context.dataStore.edit { it[KEY_PASSES] = value }
    suspend fun setLangueSortie(value: LangueSortie) =
        context.dataStore.edit { it[KEY_LANGUE] = value.name }
    suspend fun setCvUnePage(value: Boolean) = context.dataStore.edit { it[KEY_UNE_PAGE] = value }
    suspend fun setOnboardingFait(value: Boolean) = context.dataStore.edit { it[KEY_ONBOARDING] = value }

    private companion object {
        val KEY_MODE_MOTEUR = stringPreferencesKey("mode_moteur")
        val KEY_FOURNISSEUR = stringPreferencesKey("fournisseur_cloud")
        val KEY_REPLI_LOCAL = booleanPreferencesKey("repli_local")
        val KEY_ENCHAINER = booleanPreferencesKey("enchainer_fournisseurs")
        val KEY_CONTEXTE = intPreferencesKey("taille_contexte")
        val KEY_THREADS = intPreferencesKey("threads")
        val KEY_GPU = intPreferencesKey("couches_gpu")
        val KEY_EN_MEMOIRE = booleanPreferencesKey("charger_en_memoire")
        val KEY_GABARIT = stringPreferencesKey("gabarit")
        val KEY_COULEUR = stringPreferencesKey("couleur_accent")
        val KEY_PHOTO = booleanPreferencesKey("photo_cv")
        val KEY_RELECTURE = booleanPreferencesKey("relecture")
        val KEY_RELECTURE_CLOUD = booleanPreferencesKey("relecture_cloud")
        val KEY_PASSES = intPreferencesKey("passes_correction")
        val KEY_LANGUE = stringPreferencesKey("langue_sortie")
        val KEY_UNE_PAGE = booleanPreferencesKey("cv_une_page")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_fait")

        fun roleKey(role: AgentRole) = stringPreferencesKey("modele_${role.name}")

        /** Une cle par fournisseur : changer de fournisseur ne perd pas le
         *  modele choisi chez le precedent. */
        fun modeleCloudKey(f: FournisseurCloud) =
            stringPreferencesKey("modele_cloud_${f.name}")

        /** Modele leger de l'etape de preparation, lui aussi par fournisseur. */
        fun modeleLegerKey(f: FournisseurCloud) =
            stringPreferencesKey("modele_leger_${f.name}")
    }
}
