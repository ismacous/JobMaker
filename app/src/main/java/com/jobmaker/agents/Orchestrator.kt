package com.jobmaker.agents

import android.util.Log
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.DocumentsRediges
import com.jobmaker.data.model.DossierPreparation
import com.jobmaker.data.model.JobAnalysis
import com.jobmaker.data.model.JobExplanation
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Probleme
import com.jobmaker.data.model.Profile
import com.jobmaker.data.model.Review
import com.jobmaker.data.model.Strategy
import com.jobmaker.data.prefs.LangueSortie
import com.jobmaker.data.prefs.Settings
import com.jobmaker.data.prefs.configMoteur
import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.FabriqueMoteur
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import com.jobmaker.llm.MoteurTexte
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min

sealed interface PipelineEvent {
    data class Etape(val index: Int, val total: Int, val titre: String, val detail: String) : PipelineEvent
    data class Modele(val nom: String) : PipelineEvent
    data class Jeton(val texte: String) : PipelineEvent
    /** Avancement de la lecture du prompt, avant que le modele n'ecrive. */
    data class Lecture(val lus: Int, val total: Int, val dureeMs: Long) : PipelineEvent
    data class Avertissement(val message: String) : PipelineEvent
    data class CandidaturePrete(val candidature: Candidature) : PipelineEvent
    data class ExplicationPrete(val explication: JobExplanation) : PipelineEvent
    data class Echec(val message: String) : PipelineEvent
}

/**
 * Enchaine les agents pour produire une candidature complete.
 *
 * L'orchestrateur ne sait pas ou tourne le calcul : il demande un
 * [MoteurTexte] a la fabrique au debut de chaque pipeline, et ce moteur est
 * soit un GGUF charge sur le telephone, soit une API distante. Tout le reste
 * -- prompts, verification des faits, completion depuis le profil -- est
 * identique dans les deux cas, ce qui est exactement le but : la qualite du
 * resultat ne doit pas dependre de l'endroit ou le texte a ete produit.
 *
 * Le pipeline reste sequentiel. En local, c'est une contrainte materielle :
 * deux modeles quantifies charges simultanement font depasser le budget memoire
 * du processus. En distant, c'est une contrainte de quota : les offres
 * gratuites comptent les requetes par minute.
 */
class Orchestrator(
    private val fabrique: FabriqueMoteur,
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
        try {
            require(offre.isNotBlank()) { "Collez d'abord le texte de l'offre." }
            require(profile.identite.nomComplet.isNotBlank()) {
                "Renseignez au moins votre nom dans l'onglet Profil."
            }

            val moteur = fabrique.creer(settings.configMoteur()) {
                trySend(PipelineEvent.Avertissement(it))
            }

            // La relecture critique et la correction sont les deux etapes les
            // plus cheres du pipeline : en local elles doublent une generation
            // de dix minutes, d'ou leur desactivation par defaut. Sur un moteur
            // distant elles coutent quelques secondes -- et c'est exactement ce
            // qui separe un CV correct d'un CV bon. On les active donc d'office
            // des que le calcul ne se paie plus en minutes d'attente.
            val relecture = settings.relectureActive || moteur.distant
            val total = if (relecture) 4 else 2

            // ---------- 1. Analyse de l'offre et strategie ----------
            send(PipelineEvent.Etape(1, total, "Analyse et strategie",
                "Lecture de l'annonce et choix de l'angle de candidature"))
            val prep = moteur.preparer(AgentRole.ANALYSIS)
            send(PipelineEvent.Modele(prep.nomModele))

            val digest = digestAdapte(moteur, profile, prep.tailleContexte)
            val dossier = moteur.generateJson(
                serializer = DossierPreparation.serializer(),
                system = Prompts.preparationSystem,
                user = Prompts.preparationUser(offre, digest.texte),
                params = GenerationParams.precise(maxTokens = 1800),
                suppressReasoning = prep.brideRaisonnement,
                onLecturePrompt = { lus, t, ms -> trySend(PipelineEvent.Lecture(lus, t, ms)) },
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            )
            val analyse = dossier.analyse
            if (analyse.annonceIncomplete) {
                send(PipelineEvent.Avertissement(
                    "Annonce peu detaillee : l'analyse a complete avec ce que ce metier " +
                        "implique habituellement. Verifiez le resultat."
                ))
            }

            val langue = langueSortie(settings, analyse)
            val strategie = remapperIdentifiants(dossier.strategie, digest)

            // ---------- 2. Redaction du CV et de la lettre ----------
            send(PipelineEvent.Etape(2, total, "Redaction",
                "Ecriture du CV et de la lettre de motivation"))
            val redaction = moteur.preparer(AgentRole.WRITING)
            send(PipelineEvent.Modele(redaction.nomModele))

            val rediges = moteur.generateJson(
                serializer = DocumentsRediges.serializer(),
                system = Prompts.redactionSystem,
                user = Prompts.redactionUser(
                    analyse, strategie, digest.texte,
                    profile.identite.nomComplet, langue,
                    profile.recherche.disponibilite, settings.cvUnePage,
                ),
                params = GenerationParams.writing(maxTokens = 3200),
                suppressReasoning = redaction.brideRaisonnement,
                onLecturePrompt = { lus, t, ms -> trySend(PipelineEvent.Lecture(lus, t, ms)) },
                onToken = { trySend(PipelineEvent.Jeton(it)) },
            )

            var cv = completerDepuisProfil(rediges.cv, profile, langue)
            var lettre = completerLettre(rediges.lettre, profile, analyse, langue)

            var revue = Review()

            if (relecture) {
                val etapes = relireEtCorriger(
                    moteur, profile, settings, analyse, strategie, digest, langue, cv, lettre,
                    premiereEtape = 3, total = total,
                ) { trySend(it) }
                cv = etapes.cv
                lettre = etapes.lettre
                revue = etapes.revue
                etapes.avertissement?.let { send(PipelineEvent.Avertissement(it)) }
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
    // Relecture et correction
    //
    // Sorties du chemin par defaut : ce sont les deux etapes les plus chere du
    // pipeline -- elles relisent le CV, la lettre, l'annonce et le profil, puis
    // reecrivent tout -- pour un resultat que l'on relit de toute facon
    // soi-meme. Elles restent disponibles a la demande, sur une candidature
    // deja produite.
    // -----------------------------------------------------------------------

    private data class Corrigee(
        val cv: CvContent,
        val lettre: LetterContent,
        val revue: Review,
        val avertissement: String? = null,
    )

    /** Relit une candidature deja ecrite et applique les corrections. */
    fun verifierCandidature(
        candidature: Candidature,
        profile: Profile,
        settings: Settings,
    ): Flow<PipelineEvent> = channelFlow {
        try {
            val moteur = fabrique.creer(settings.configMoteur()) {
                trySend(PipelineEvent.Avertissement(it))
            }
            val contexte = moteur.preparer(AgentRole.REVIEW).tailleContexte
            val digest = digestAdapte(moteur, profile, contexte)
            val langue = candidature.cv.langue.ifBlank { "fr" }
            val resultat = relireEtCorriger(
                moteur, profile, settings, candidature.analyse, candidature.strategie, digest,
                langue, candidature.cv, candidature.lettre,
                premiereEtape = 1, total = 2,
            ) { trySend(it) }
            resultat.avertissement?.let { send(PipelineEvent.Avertissement(it)) }

            val rapport = FactCheck.verifier(profile, candidature.analyse, resultat.cv, resultat.lettre)
            send(PipelineEvent.CandidaturePrete(
                candidature.copy(
                    cv = resultat.cv,
                    lettre = resultat.lettre,
                    revue = resultat.revue,
                    scoreAts = rapport.scoreAts,
                    modifieLe = System.currentTimeMillis(),
                )
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Echec de la verification", e)
            send(PipelineEvent.Echec(e.message ?: "Erreur inattendue"))
        }
    }

    private suspend fun relireEtCorriger(
        moteur: MoteurTexte,
        profile: Profile,
        settings: Settings,
        analyse: JobAnalysis,
        strategie: Strategy,
        digest: ProfileDigest,
        langue: String,
        cvInitial: CvContent,
        lettreInitiale: LetterContent,
        premiereEtape: Int,
        total: Int,
        emettre: (PipelineEvent) -> Unit,
    ): Corrigee {
        var cv = cvInitial
        var lettre = lettreInitiale

        emettre(PipelineEvent.Etape(premiereEtape, total, "Relecture critique",
            "Recherche d'inventions, d'oublis et de maladresses"))
        val relecture = moteur.preparer(AgentRole.REVIEW)
        emettre(PipelineEvent.Modele(relecture.nomModele))

        val revueIa = moteur.generateJson(
            serializer = Review.serializer(),
            system = Prompts.relecteurSystem,
            user = Prompts.relecteurUser(
                analyse, digest.texte, cv.texteIntegral(), lettre.texteIntegral(),
            ),
            params = GenerationParams.precise(maxTokens = 1400),
            suppressReasoning = relecture.brideRaisonnement,
            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
            onToken = { emettre(PipelineEvent.Jeton(it)) },
        )

        // Les controles mecaniques passent apres l'IA et la completent : ils ne
        // ratent jamais un employeur, un diplome ou un chiffre inconnu.
        val rapport = FactCheck.verifier(profile, analyse, cv, lettre)
        var revue = fusionner(revueIa, rapport)

        val avertissement = if (rapport.aDesAlertes) {
            "Verification automatique : " + listOfNotNull(
                rapport.organisationsSuspectes.takeIf { it.isNotEmpty() }
                    ?.let { "organisations absentes du profil (${it.joinToString(", ")})" },
                rapport.diplomesSuspects.takeIf { it.isNotEmpty() }
                    ?.let { "diplomes absents du profil (${it.joinToString(", ")})" },
                rapport.chiffresSuspects.takeIf { it.isNotEmpty() }
                    ?.let { "chiffres non presents dans le profil (${it.joinToString(", ")})" },
            ).joinToString(" ; ") + ". Ils vont etre retires."
        } else null

        val besoinCorrection = revue.faitsInventes.isNotEmpty() ||
            revue.problemes.any { it.gravite.lowercase() in setOf("bloquant", "important") } ||
            revue.scoreGlobal < 85

        if (besoinCorrection && settings.passesCorrection > 0) {
            emettre(PipelineEvent.Etape(premiereEtape + 1, total, "Correction",
                "Application des corrections de la relecture"))
            val correction = moteur.preparer(AgentRole.WRITING)
            emettre(PipelineEvent.Modele(correction.nomModele))

            repeat(min(settings.passesCorrection, 2)) { passe ->
                val cvCorrige = runCatching {
                    moteur.generateJson(
                        serializer = CvContent.serializer(),
                        system = Prompts.redacteurCvSystem,
                        user = Prompts.correctionCvUser(
                            analyse, strategie, digest.texte,
                            prettyJson.encodeToString(cv), revue, langue,
                        ),
                        params = GenerationParams.writing(maxTokens = 2200),
                        suppressReasoning = correction.brideRaisonnement,
                        onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
                        onToken = { emettre(PipelineEvent.Jeton(it)) },
                    )
                }.getOrNull()
                if (cvCorrige != null) cv = completerDepuisProfil(cvCorrige, profile, langue)

                if (revue.problemes.any { it.zone.contains("lettre", true) } ||
                    revue.faitsInventes.isNotEmpty()
                ) {
                    val lettreCorrigee = runCatching {
                        moteur.generateJson(
                            serializer = LetterContent.serializer(),
                            system = Prompts.redacteurLettreSystem,
                            user = Prompts.correctionLettreUser(
                                analyse, digest.texte,
                                prettyJson.encodeToString(lettre), revue, langue,
                            ),
                            params = GenerationParams.writing(maxTokens = 1600),
                            suppressReasoning = correction.brideRaisonnement,
                            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
                            onToken = { emettre(PipelineEvent.Jeton(it)) },
                        )
                    }.getOrNull()
                    if (lettreCorrigee != null) {
                        lettre = completerLettre(lettreCorrigee, profile, analyse, langue)
                    }
                }

                val rapport2 = FactCheck.verifier(profile, analyse, cv, lettre)
                revue = fusionner(revue.copy(faitsInventes = emptyList()), rapport2)
                if (!rapport2.aDesAlertes) return@repeat
                Log.i(TAG, "Passe de correction ${passe + 1} : alertes restantes")
            }
        }

        return Corrigee(cv, lettre, revue, avertissement)
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
            val moteur = fabrique.creer(settings.configMoteur()) {
                trySend(PipelineEvent.Avertissement(it))
            }
            val pret = moteur.preparer(AgentRole.EXPLAIN)
            send(PipelineEvent.Modele(pret.nomModele))

            val digest = if (profile.identite.nomComplet.isBlank()) ProfileDigest("", emptyMap())
            else digestAdapte(moteur, profile, pret.tailleContexte)

            val explication = moteur.generateJson(
                serializer = JobExplanation.serializer(),
                system = Prompts.explicateurSystem,
                user = Prompts.explicateurUser(offre, digest.texte),
                params = GenerationParams.explaining(maxTokens = 2400),
                suppressReasoning = pret.brideRaisonnement,
                onLecturePrompt = { lus, total, ms -> trySend(PipelineEvent.Lecture(lus, total, ms)) },
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

    /**
     * Choisit entre profil detaille et profil resume selon la place disponible
     * dans la fenetre de contexte du moteur prepare.
     */
    private suspend fun digestAdapte(
        moteur: MoteurTexte,
        profile: Profile,
        tailleContexte: Int,
    ): ProfileDigest {
        val complet = ProfileSerializer.digest(profile)
        // On reserve la moitie du contexte au prompt systeme, a l'offre et a la sortie.
        val budget = tailleContexte / 2
        val tokens = moteur.tokenCount(complet.texte)
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
        // Les formations ne viennent JAMAIS du modele. Sur une annonce exigeant
        // un diplome que le candidat n'a pas, il recopiait l'exigence dans les
        // formations : un faux diplome d'Etat, verifiable en un appel. Aucune
        // consigne ne resiste durablement a cette tentation, alors on ne la lui
        // laisse pas -- les formations sont recopiees du profil, telles quelles.
        val formations = profile.formations.map { f ->
            com.jobmaker.data.model.CvFormation(
                diplome = f.diplome,
                etablissement = f.etablissement,
                lieu = f.lieu,
                periode = f.periode,
                detail = listOfNotNull(
                    f.mention.takeIf { it.isNotBlank() },
                    f.matieres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                ).joinToString(" - "),
            )
        }
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
            formations = formations,
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
            rapport.diplomesSuspects.forEach {
                add(Probleme("bloquant", "formation",
                    "Le diplome \"$it\" ne figure pas dans votre profil",
                    "Le retirer. Annoncer un diplome que l'on n'a pas est verifiable " +
                        "et disqualifie la candidature"))
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
            rapport.chiffresSuspects.map { "Chiffre inconnu : $it" } +
            rapport.diplomesSuspects.map { "Diplome inconnu : $it" }).distinct()

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
