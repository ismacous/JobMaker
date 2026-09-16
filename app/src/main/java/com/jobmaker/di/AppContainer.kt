package com.jobmaker.di

import android.content.Context
import com.jobmaker.agents.Orchestrator
import com.jobmaker.data.db.JobMakerDatabase
import com.jobmaker.data.prefs.CoffreCles
import com.jobmaker.data.prefs.SettingsRepository
import com.jobmaker.data.repo.CandidatureRepository
import com.jobmaker.data.repo.ExplanationRepository
import com.jobmaker.data.repo.ProfileRepository
import com.jobmaker.llm.FabriqueMoteur
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.ModelManager
import com.jobmaker.llm.cloud.ClientCloud
import com.jobmaker.render.DocumentExporter
import com.jobmaker.work.MoteurCandidature

/**
 * Localisateur de services.
 *
 * Une application mono-utilisateur sans tests d'integration n'a pas besoin d'un
 * framework d'injection : un conteneur construit au demarrage suffit et reste
 * lisible.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database = JobMakerDatabase.get(appContext)

    val profileRepository = ProfileRepository(database.profileDao())
    val candidatureRepository = CandidatureRepository(database.candidatureDao())
    val explanationRepository = ExplanationRepository(database.explanationDao())
    val settingsRepository = SettingsRepository(appContext)

    val modelManager = ModelManager(appContext)
    val llmRuntime = LlmRuntime()

    /** Cles d'API, chiffrees par le materiel du telephone. */
    val coffreCles = CoffreCles(appContext)
    val clientCloud = ClientCloud()

    /**
     * La cle n'est pas injectee une fois pour toutes : la fabrique la relit au
     * coffre a chaque pipeline. Changer de cle dans les reglages prend effet
     * immediatement, et aucune copie en clair ne survit entre deux generations.
     */
    val fabriqueMoteur = FabriqueMoteur(
        runtime = llmRuntime,
        modelManager = modelManager,
        client = clientCloud,
        lireCle = { fournisseur -> coffreCles.cle(fournisseur) },
    )

    val orchestrator = Orchestrator(fabriqueMoteur)
    val documentExporter = DocumentExporter(appContext)

    /**
     * Detient la generation en cours. Volontairement dans le conteneur et non
     * dans un ViewModel : le travail doit survivre a la fermeture de l'ecran.
     */
    val moteurCandidature = MoteurCandidature(
        appContext = appContext,
        orchestrator = orchestrator,
        llmRuntime = llmRuntime,
        profileRepository = profileRepository,
        candidatureRepository = candidatureRepository,
        settingsRepository = settingsRepository,
    )
}
