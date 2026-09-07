package com.jobmaker.agents

import android.util.Log
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.JobAnalysis
import com.jobmaker.data.model.JobExplanation
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Probleme
import com.jobmaker.data.model.Profile
import com.jobmaker.data.model.Review
import com.jobmaker.data.model.Strategy
import com.jobmaker.data.prefs.LangueSortie
import com.jobmaker.data.prefs.Settings
import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.ModelManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min

sealed interface PipelineEvent {
    data class Etape(val index: Int, val total: Int, val titre: String, val detail: String) : PipelineEvent
    data class Modele(val nom: String) : PipelineEvent
    data class Jeton(val texte: String) : PipelineEvent
    data class Avertissement(val message: String) : PipelineEvent
    data class CandidaturePrete(val candidature: Candidature) : PipelineEvent
    data class ExplicationPrete(val explication: JobExplanation) : PipelineEvent
    data class Echec(val message: String) : PipelineEvent
}

/**
 * Enchaine les agents pour produire une candidature complete.
 *
 * Le pipeline est sequentiel et non parallele, pour une raison materielle :
 * deux modeles quantifies charges simultanement sur un telephone font
 * depasser le budget memoire du processus. Chaque etape peut cependant
 * utiliser un modele different -- l'orchestrateur decharge et recharge entre
 * les etapes. Affecter le meme modele a tous les roles supprime ces
 * rechargements et reste le reglage le plus rapide.
 */
class Orchestrator(
    private val runtime: LlmRuntime,
    private val modelManager: ModelManager,
) {

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    // -----------------------------------------------------------------------
    // Pipeline complet : offre -> CV + lettre
    // -----------------------------------------------------------------------

    fun genererCandidature(
        offre: String,
        profile: Profile,
        settings: Settings,
        candidatureExistante: Candidature? = null,
    ): Flow<PipelineEvent> = channelFlow {
        val relecture = settings.relectureActive
        val total = if (relecture) 6 else 4

        try {
            require(offre.isNotBlank()) { "Collez d'abord le texte de l'offre." }
            require(profile.identite.nomComplet.isNotBlank()) {
                "Renseignez au moins votre nom dans l'onglet Profil."
            }

            // ---------- 1. Analyse de l'offre ----------
            send(PipelineEvent.Etape(1, total, "Analyse de l'offre",
                "Lecture de l'annonce et extraction des exigences reelles"))
            val (analyseModel, analyseNoThink) = charger(AgentRole.ANALYSIS, settings)
            send(PipelineEvent.Modele(analyseModel))

            val analyse = runtime.generateJson(
                serializer = JobAnalysis.serializer(),
                system = Prompts.analysteSystem,
                user = Prompts.analysteUser(offre),
                params = GenerationParams.precise(maxTokens = 1200),
                suppressReasoning = analyseNoThink,
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            )
            if (analyse.annonceIncomplete) {
                send(PipelineEvent.Avertissement(
                    "Annonce peu detaillee : l'analyse a complete avec ce que ce metier " +
                        "implique habituellement. Verifiez le resultat."
                ))
            }

            val langue = langueSortie(settings, analyse)

            // ---------- 2. Strategie ----------
            send(PipelineEvent.Etape(2, total, "Strategie de candidature",
                "Choix de l'angle et des experiences a mettre en avant"))
            val digest = digestAdapte(profile, settings)
            val (strategieModel, strategieNoThink) = charger(AgentRole.STRATEGY, settings)
            send(PipelineEvent.Modele(strategieModel))

            val strategieBrute = runtime.generateJson(
                serializer = Strategy.serializer(),
                system = Prompts.strategeSystem,
                user = Prompts.strategeUser(analyse, digest.texte),
                params = GenerationParams.precise(maxTokens = 1400),
                suppressReasoning = strategieNoThink,
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            )
            val strategie = remapperIdentifiants(strategieBrute, digest)

            // ---------- 3. Redaction du CV ----------
            send(PipelineEvent.Etape(3, total, "Redaction du CV",
                "Reecriture des experiences pour cette offre precise"))
            val (redactionModel, redactionNoThink) = charger(AgentRole.WRITING, settings)
            send(PipelineEvent.Modele(redactionModel))

            var cv = runtime.generateJson(
                serializer = CvContent.serializer(),
                system = Prompts.redacteurCvSystem,
                user = Prompts.redacteurCvUser(analyse, strategie, digest.texte, langue, settings.cvUnePage),
                params = GenerationParams.writing(maxTokens = 2200),
                suppressReasoning = redactionNoThink,
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            ).let { completerDepuisProfil(it, profile, langue) }

            // ---------- 4. Redaction de la lettre ----------
            send(PipelineEvent.Etape(4, total, "Redaction de la lettre",
                "Lettre de motivation adaptee au poste"))
            var lettre = runtime.generateJson(
                serializer = LetterContent.serializer(),
                system = Prompts.redacteurLettreSystem,
                user = Prompts.redacteurLettreUser(
                    analyse, strategie, digest.texte,
                    profile.identite.nomComplet, langue, profile.recherche.disponibilite,
                ),
                params = GenerationParams.writing(maxTokens = 1600),
                suppressReasoning = redactionNoThink,
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            ).let { completerLettre(it, profile, analyse, langue) }

            var revue = Review()

            if (relecture) {
                // ---------- 5. Relecture ----------
                send(PipelineEvent.Etape(5, total, "Relecture critique",
                    "Recherche d'inventions, d'oublis et de maladresses"))
                val (relectureModel, relectureNoThink) = charger(AgentRole.REVIEW, settings)
                send(PipelineEvent.Modele(relectureModel))

                val revueIa = runtime.generateJson(
                    serializer = Review.serializer(),
                    system = Prompts.relecteurSystem,
                    user = Prompts.relecteurUser(
                        analyse, digest.texte, cv.texteIntegral(), lettre.texteIntegral(),
                    ),
                    params = GenerationParams.precise(maxTokens = 1400),
                    suppressReasoning = relectureNoThink,
                    onToken = { trySend(PipelineEvent.Jeton(it)) },
                )

                // Les controles mecaniques passent apres l'IA et la completent :
                // ils ne ratent jamais un employeur ou un chiffre inconnu.
                val rapport = FactCheck.verifier(profile, analyse, cv, lettre)
                revue = fusionner(revueIa, rapport)

                if (rapport.aDesAlertes) {
                    send(PipelineEvent.Avertissement(
                        "Verification automatique : " +
                            listOfNotNull(
                                rapport.organisationsSuspectes.takeIf { it.isNotEmpty() }
                                    ?.let { "organisations absentes du profil (${it.joinToString(", ")})" },
                                rapport.chiffresSuspects.takeIf { it.isNotEmpty() }
                                    ?.let { "chiffres non presents dans le profil (${it.joinToString(", ")})" },
                            ).joinToString(" ; ") + ". Ils vont etre retires."
                    ))
                }

                // ---------- 6. Correction ----------
                val besoinCorrection = revue.faitsInventes.isNotEmpty() ||
                    revue.problemes.any { it.gravite.lowercase() in setOf("bloquant", "important") } ||
                    revue.scoreGlobal < 85

                if (besoinCorrection && settings.passesCorrection > 0) {
                    send(PipelineEvent.Etape(6, total, "Correction",
                        "Application des corrections de la relecture"))
                    val (correctionModel, correctionNoThink) = charger(AgentRole.WRITING, settings)
                    send(PipelineEvent.Modele(correctionModel))

                    repeat(min(settings.passesCorrection, 2)) { passe ->
                        val cvCorrige = runCatching {
                            runtime.generateJson(
                                serializer = CvContent.serializer(),
                                system = Prompts.redacteurCvSystem,
                                user = Prompts.correctionCvUser(
                                    analyse, strategie, digest.texte,
                                    prettyJson.encodeToString(cv), revue, langue,
                                ),
                                params = GenerationParams.writing(maxTokens = 2200),
                                suppressReasoning = correctionNoThink,
                                onToken = { trySend(PipelineEvent.Jeton(it)) },
                            )
                        }.getOrNull()
                        if (cvCorrige != null) cv = completerDepuisProfil(cvCorrige, profile, langue)

                        if (revue.problemes.any { it.zone.contains("lettre", true) } ||
                            revue.faitsInventes.isNotEmpty()
                        ) {
                            val lettreCorrigee = runCatching {
                                runtime.generateJson(
                                    serializer = LetterContent.serializer(),
                                    system = Prompts.redacteurLettreSystem,
                                    user = Prompts.correctionLettreUser(
                                        analyse, digest.texte,
                                        prettyJson.encodeToString(lettre), revue, langue,
                                    ),
                                    params = GenerationParams.writing(maxTokens = 1600),
                                    suppressReasoning = correctionNoThink,
                                    onToken = { trySend(PipelineEvent.Jeton(it)) },
                                )
                            }.getOrNull()
                            if (lettreCorrigee != null) {
                                lettre = completerLettre(lettreCorrigee, profile, analyse, langue)
                            }
                        }

                        // Nouvelle verification mecanique apres correction.
                        val rapport2 = FactCheck.verifier(profile, analyse, cv, lettre)
                        revue = fusionner(revue.copy(faitsInventes = emptyList()), rapport2)
                        if (!rapport2.aDesAlertes) return@repeat
                        Log.i(TAG, "Passe de correction ${passe + 1} : alertes restantes")
                    }
                }
            }

            val rapportFinal = FactCheck.verifier(profile, analyse, cv, lettre)

            val candidature = (candidatureExistante ?: Candidature()).copy(
                offreTexte = offre,
                analyse = analyse,
                strategie = strategie,
                cv = cv,
                lettre = lettre,
                revue = revue.copy(scoreGlobal = revue.scoreGlobal.coerceAtLeast(0)),
                gabarit = settings.gabaritParDefaut,
                couleurAccent = settings.couleurAccent,
                avecPhoto = settings.photoSurCv && profile.identite.photoUri.isNotBlank(),
                scoreAts = rapportFinal.scoreAts,
                modifieLe = System.currentTimeMillis(),
            )

            send(PipelineEvent.CandidaturePrete(candidature))
        } catch (e: LlmException) {
            send(PipelineEvent.Echec(e.message ?: "Erreur du moteur d'inference"))
        } catch (e: IllegalArgumentException) {
            send(PipelineEvent.Echec(e.message ?: "Donnees manquantes"))
        } catch (e: Exception) {
            Log.e(TAG, "Echec du pipeline", e)
            send(PipelineEvent.Echec(e.message ?: "Erreur inattendue"))
        }
    }

    // -----------------------------------------------------------------------
    // Explication de poste
    // -----------------------------------------------------------------------

    fun expliquerPoste(
        offre: String,
        profile: Profile,
        settings: Settings,
    ): Flow<PipelineEvent> = channelFlow {
        try {
            require(offre.isNotBlank()) { "Collez l'annonce ou l'intitule du poste." }

            send(PipelineEvent.Etape(1, 1, "Analyse du metier",
                "Explication du poste, du quotidien et de l'adequation avec votre profil"))
            val (model, noThink) = charger(AgentRole.EXPLAIN, settings)
            send(PipelineEvent.Modele(model))

            val digest = if (profile.identite.nomComplet.isBlank()) ProfileDigest("", emptyMap())
            else digestAdapte(profile, settings)

            val explication = runtime.generateJson(
                serializer = JobExplanation.serializer(),
                system = Prompts.explicateurSystem,
                user = Prompts.explicateurUser(offre, digest.texte),
                params = GenerationParams.explaining(maxTokens = 2400),
                suppressReasoning = noThink,
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            )
            send(PipelineEvent.ExplicationPrete(explication))
        } catch (e: Exception) {
            send(PipelineEvent.Echec(e.message ?: "Erreur inattendue"))
        }
    }

    // -----------------------------------------------------------------------
    // Outils internes
    // -----------------------------------------------------------------------

    /** Charge le modele affecte a [role]. Retourne son nom et s'il faut brider son raisonnement. */
    private suspend fun charger(role: AgentRole, settings: Settings): Pair<String, Boolean> {
        val installes = modelManager.installed.value
        if (installes.isEmpty()) {
            throw LlmException(
                "Aucun modele installe. Ouvrez Reglages > Modeles d'IA et telechargez " +
                    "au moins un modele (Wi-Fi conseille)."
            )
        }
        val demande = settings.modelePour(role)
        val choisi = installes.firstOrNull { it.id == demande } ?: installes.first()
        val entree = modelManager.catalog.byId(choisi.id)

        val contexte = min(
            settings.tailleContexte,
            entree?.contextMax ?: settings.tailleContexte,
        ).coerceAtLeast(2048)

        runtime.ensureLoaded(
            modelId = choisi.id,
            filePath = choisi.file.absolutePath,
            contextSize = contexte,
            threads = settings.threads,
            gpuLayers = settings.couchesGpu,
        )
        return choisi.displayName to (entree?.emitsReasoning ?: false)
    }

    /**
     * Choisit entre profil detaille et profil resume selon la place disponible
     * dans la fenetre de contexte du modele charge.
     */
    private suspend fun digestAdapte(profile: Profile, settings: Settings): ProfileDigest {
        val complet = ProfileSerializer.digest(profile)
        val contexte = runtime.currentModel?.contextSize ?: settings.tailleContexte
        // On reserve la moitie du contexte au prompt systeme, a l'offre et a la sortie.
        val budget = contexte / 2
        val tokens = runtime.tokenCount(complet.texte)
        return if (tokens <= budget) complet else ProfileSerializer.digestCourt(profile)
    }

    private fun langueSortie(settings: Settings, analyse: JobAnalysis): String =
        when (settings.langueSortie) {
            LangueSortie.FR -> "fr"
            LangueSortie.EN -> "en"
            LangueSortie.AUTO -> analyse.langue.lowercase().take(2).ifBlank { "fr" }
        }

    /** Retraduit les etiquettes E1/E2 en identifiants reels d'experiences. */
    private fun remapperIdentifiants(strategie: Strategy, digest: ProfileDigest): Strategy =
        strategie.copy(
            experiencesPrioritaires = strategie.experiencesPrioritaires.map { p ->
                p.copy(experienceId = digest.idReel(p.experienceId) ?: p.experienceId)
            }
        )

    /**
     * Complete le CV avec ce que le modele n'a pas le droit de produire
     * (langues du profil, certifications, permis) et corrige les oublis les plus
     * frequents. L'identite n'est jamais generee : elle est injectee au rendu.
     */
    private fun completerDepuisProfil(cv: CvContent, profile: Profile, langue: String): CvContent {
        val langues = cv.langues.ifEmpty {
            profile.langues.map { com.jobmaker.data.model.CvLangue(it.nom, it.niveau) }
        }
        val certifs = cv.certifications.ifEmpty {
            profile.certifications.map { c ->
                listOf(c.nom, c.organisme, c.annee).filter { it.isNotBlank() }.joinToString(" - ")
            }
        }
        val infos = cv.infosComplementaires.ifEmpty {
            buildList {
                if (profile.permis.isNotEmpty()) add("Permis ${profile.permis.joinToString(", ")}")
                if (profile.recherche.vehicule) add("Vehicule personnel")
                if (profile.recherche.disponibilite.isNotBlank())
                    add("Disponible : ${profile.recherche.disponibilite}")
            }
        }
        return cv.copy(
            langue = langue,
            titre = cv.titre.ifBlank { profile.identite.titre.ifBlank { profile.recherche.posteVise } },
            langues = langues,
            certifications = certifs,
            infosComplementaires = infos,
        )
    }

    private fun completerLettre(
        lettre: LetterContent,
        profile: Profile,
        analyse: JobAnalysis,
        langue: String,
    ): LetterContent = lettre.copy(
        langue = langue,
        objet = lettre.objet.ifBlank {
            if (langue == "en") "Application for the ${analyse.poste} position"
            else "Candidature au poste de ${analyse.poste}"
        },
        lieuEtDate = lettre.lieuEtDate.ifBlank {
            val ville = profile.identite.ville
            val date = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.FRENCH)
                .format(java.util.Date())
            if (ville.isNotBlank()) "$ville, le $date" else "Le $date"
        },
        salutation = lettre.salutation.ifBlank {
            if (langue == "en") "Dear Hiring Manager," else "Madame, Monsieur,"
        },
        formulePolitesse = lettre.formulePolitesse.ifBlank {
            if (langue == "en") "Kind regards,"
            else "Je vous prie d'agreer, Madame, Monsieur, l'expression de mes salutations distinguees."
        },
        signature = lettre.signature.ifBlank { profile.identite.nomComplet },
    )

    /** Injecte les constats mecaniques dans la revue de l'IA. */
    private fun fusionner(revue: Review, rapport: FactCheck.Rapport): Review {
        val problemesAjoutes = buildList {
            rapport.organisationsSuspectes.forEach {
                add(Probleme("bloquant", "experience",
                    "L'organisation \"$it\" n'apparait pas dans le profil",
                    "Remplacer par le nom exact figurant dans le profil, ou supprimer la ligne"))
            }
            rapport.chiffresSuspects.forEach {
                add(Probleme("bloquant", "experience",
                    "Le chiffre \"$it\" n'apparait pas dans le profil",
                    "Supprimer ce chiffre ou le remplacer par une formulation sans chiffre"))
            }
            if (rapport.premierePersonneDansCv) {
                add(Probleme("important", "accroche",
                    "Le CV emploie la premiere personne",
                    "Reformuler sans \"je\" ni \"mon\""))
            }
            if (rapport.puceTropLongues > 0) {
                add(Probleme("mineur", "experience",
                    "${rapport.puceTropLongues} puce(s) depassent deux lignes",
                    "Couper en deux ou raccourcir"))
            }
            if (rapport.motsLettre > 400) {
                add(Probleme("important", "lettre",
                    "La lettre fait ${rapport.motsLettre} mots",
                    "Ramener a 330 mots maximum"))
            }
        }

        val inventes = (revue.faitsInventes +
            rapport.organisationsSuspectes.map { "Organisation inconnue : $it" } +
            rapport.chiffresSuspects.map { "Chiffre inconnu : $it" }).distinct()

        val score = if (inventes.isNotEmpty()) min(revue.scoreGlobal, 55) else revue.scoreGlobal

        return revue.copy(
            scoreGlobal = score,
            faitsInventes = inventes,
            motsClesManquants = (revue.motsClesManquants + rapport.motsClesAbsents).distinct(),
            problemes = (revue.problemes + problemesAjoutes)
                .distinctBy { it.probleme.lowercase() },
        )
    }

    private companion object { const val TAG = "Orchestrator" }
}
