package com.jobmaker.data.repo

import com.jobmaker.data.db.CandidatureDao
import com.jobmaker.data.db.CandidatureEntity
import com.jobmaker.data.db.ExplanationDao
import com.jobmaker.data.db.ExplanationEntity
import com.jobmaker.data.db.ProfileDao
import com.jobmaker.data.db.ProfileEntity
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.JobExplanation
import com.jobmaker.data.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

class ProfileRepository(private val dao: ProfileDao) {

    val profile: Flow<Profile> = dao.observe().map { entity ->
        entity?.json?.let { runCatching { appJson.decodeFromString<Profile>(it) }.getOrNull() }
            ?: Profile()
    }

    suspend fun get(): Profile =
        dao.get()?.json?.let { runCatching { appJson.decodeFromString<Profile>(it) }.getOrNull() }
            ?: Profile()

    suspend fun save(profile: Profile) {
        dao.upsert(ProfileEntity(json = appJson.encodeToString(profile)))
    }

    /** Sauvegarde exportable : l'utilisateur peut ainsi ne jamais reperdre sa saisie. */
    suspend fun exportJson(): String =
        Json { prettyPrint = true; encodeDefaults = true }.encodeToString(get())

    suspend fun importJson(text: String): Result<Profile> = runCatching {
        val p = appJson.decodeFromString<Profile>(text)
        save(p)
        p
    }
}

class CandidatureRepository(private val dao: CandidatureDao) {

    val all: Flow<List<Candidature>> = dao.observeAll().map { list ->
        list.mapNotNull { runCatching { appJson.decodeFromString<Candidature>(it.json) }.getOrNull() }
    }

    fun observe(id: String): Flow<Candidature?> = dao.observeOne(id).map { entity ->
        entity?.json?.let { runCatching { appJson.decodeFromString<Candidature>(it) }.getOrNull() }
    }

    suspend fun get(id: String): Candidature? =
        dao.get(id)?.json?.let { runCatching { appJson.decodeFromString<Candidature>(it) }.getOrNull() }

    suspend fun save(candidature: Candidature) {
        val updated = candidature.copy(modifieLe = System.currentTimeMillis())
        dao.upsert(
            CandidatureEntity(
                id = updated.id,
                json = appJson.encodeToString(updated),
                poste = updated.analyse.poste.ifBlank { "Poste sans titre" },
                entreprise = updated.analyse.entreprise,
                statut = updated.statut.name,
                scoreAts = updated.scoreAts,
                creeLe = updated.creeLe,
                modifieLe = updated.modifieLe,
            )
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun count(): Int = dao.count()
}

class ExplanationRepository(private val dao: ExplanationDao) {

    data class Entry(
        val id: String,
        val offreTexte: String,
        val explication: JobExplanation,
        val creeLe: Long,
    )

    val all: Flow<List<Entry>> = dao.observeAll().map { list ->
        list.mapNotNull { e ->
            runCatching {
                Entry(e.id, e.offreTexte, appJson.decodeFromString(e.json), e.creeLe)
            }.getOrNull()
        }
    }

    suspend fun save(offreTexte: String, explication: JobExplanation): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            ExplanationEntity(
                id = id,
                offreTexte = offreTexte,
                json = appJson.encodeToString(explication),
                intitule = explication.intituleClair,
                creeLe = System.currentTimeMillis(),
            )
        )
        return id
    }

    suspend fun delete(id: String) = dao.delete(id)
}
