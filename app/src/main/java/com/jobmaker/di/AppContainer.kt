package com.jobmaker.di

import android.content.Context
import com.jobmaker.agents.Orchestrator
import com.jobmaker.data.db.JobMakerDatabase
import com.jobmaker.data.prefs.SettingsRepository
import com.jobmaker.data.repo.CandidatureRepository
import com.jobmaker.data.repo.ExplanationRepository
import com.jobmaker.data.repo.ProfileRepository
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.ModelManager
import com.jobmaker.render.DocumentExporter

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
    val orchestrator = Orchestrator(llmRuntime, modelManager)
    val documentExporter = DocumentExporter(appContext)
}
