package com.jobmaker.agents

import android.util.Log
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.DocumentsRediges
import com.jobmaker.data.model.DossierComplet
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
import com.jobmaker.llm.AgentRole
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.ModelManager
import com.jobmaker.llm.TraceAppel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

sealed interface PipelineEvent {
    data class Etape(val index: Int, val total: Int, val titre: String, val detail: String) : PipelineEvent
    /** [detail] decrit le reglage effectif : fenetre, threads, mode de chargement. */
    data class Modele(val nom: String, val detail: String = "") : PipelineEvent
    /** Texte produit depuis le dernier evenement, et son cout en tokens. */
    data class Jeton(val texte: String, val tokens: Int) : PipelineEvent
    /** Bilan chiffre d'un appel au modele, une fois l'appel termine. */
    data class Mesure(val trace: TraceAppel) : PipelineEvent
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
        val etapesRedaction = if (settings.analyseApprofondie) 2 else 1
        val total = etapesRedaction + if (relecture) 2 else 0

        try {
            require(offre.isNotBlank()) { "Collez d'abord le texte de l'offre." }
            require(profile.identite.nomComplet.isNotBlank()) {
                "Renseignez au moins votre nom dans l'onglet Profil."
            }

            val offreUtile = tronquerOffre(offre) { trySend(PipelineEvent.Avertissement(it)) }
            val produit = if (settings.analyseApprofondie) {
                enDeuxTemps(offreUtile, profile, settings, total) { trySend(it) }
            } else {
                enUnSeulAppel(offreUtile, profile, settings, total) { trySend(it) }
            }

            val analyse = produit.dossier.analyse
            if (analyse.annonceIncomplete) {
                send(PipelineEvent.Avertissement(
                    "Annonce peu detaillee : l'analyse a complete avec ce que ce metier " +
                        "implique habituellement. Verifiez le resultat."
                ))
            }

            val digest = produit.digest
            val langue = produit.langue
            val strategie = produit.dossier.strategie
            var cv = completerDepuisProfil(produit.dossier.cv, profile, langue)
            var lettre = completerLettre(produit.dossier.lettre, profile, analyse, langue)

            var revue = Review()

            if (relecture) {
                val etapes = relireEtCorriger(
                    profile, settings, analyse, strategie, digest, langue, cv, lettre,
                    premiereEtape = etapesRedaction + 1, total = total,
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

    /** Ce que produit un chemin de generation, quel qu'il soit. */
    private data class Produit(
        val dossier: DossierComplet,
        val digest: ProfileDigest,
        val langue: String,
    )

    /**
     * Chemin par defaut : un seul appel au modele.
     *
     * Le decoupage en deux appels coutait deux fois le prompt -- consignes,
     * annonce et profil relus depuis zero -- plus la mise par ecrit d'une
     * analyse complete dont le seul lecteur etait l'appel suivant. Mesure sur
     * un S25 Ultra : 8300 tokens lus et 2400 ecrits, pour 1500 tokens utiles,
     * soit vingt-quatre minutes.
     *
     * Le modele raisonne toujours avant d'ecrire : les champs "analyse" et
     * "strategie" viennent en tete du schema, il les remplit donc en premier et
     * les relit ensuite -- mais dans la meme reponse, sans rien recalculer.
     */
    private suspend fun enUnSeulAppel(
        offre: String,
        profile: Profile,
        settings: Settings,
        total: Int,
        emettre: (PipelineEvent) -> Unit,
    ): Produit {
        emettre(PipelineEvent.Etape(1, total, "Redaction",
            "Analyse de l'annonce, puis ecriture du CV et de la lettre"))

        // Le profil est mis en forme avant le chargement : sa taille reelle
        // decide de la fenetre a reserver, et donc de la memoire prise par le
        // cache d'attention. Une fenetre de 10240 la ou 6144 suffisent reserve
        // un demi-gigaoctet pour rien -- qu'Android reprend en evincant les
        // pages du modele, qu'il faut alors relire sur le stockage.
        val digest = digestAdapte(profile, settings, offre)
        val langue = langueSortie(settings, offre)
        val contexte = contexteVoulu(
            settings,
            tokensPrompt = runtime.estimateTokens(Prompts.candidatureSystem) +
                runtime.estimateTokens(offre) + runtime.estimateTokens(digest.texte),
            budgetEcriture = MAX_APPEL_UNIQUE,
        )

        val (modele, noThink) = charger(AgentRole.WRITING, settings, contexte)
        emettre(PipelineEvent.Modele(modele, detailMoteur(settings)))

        val dossier = runtime.generateJson(
            serializer = DossierComplet.serializer(),
            system = Prompts.candidatureSystem,
            user = Prompts.candidatureUser(
                offre, digest.texte, profile.identite.nomComplet, langue,
                profile.recherche.disponibilite, settings.cvUnePage,
            ),
            params = GenerationParams.writing(maxTokens = MAX_APPEL_UNIQUE),
            suppressReasoning = noThink,
            etape = "Candidature complete",
            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
            onToken = { texte, n -> emettre(PipelineEvent.Jeton(texte, n)) },
            onTrace = { emettre(PipelineEvent.Mesure(it)) },
        )
        return Produit(dossier, digest, langue)
    }

    /**
     * Chemin "analyse approfondie" : l'annonce est etudiee dans un appel
     * separe, avec le detail complet -- souhaits, outils, attentes implicites,
     * ecarts et reponses, experiences classees une a une. Ce detail nourrit
     * ensuite la redaction et remplit l'onglet "Offre analysee".
     *
     * Il se paie : une seconde lecture entiere des consignes, de l'annonce et
     * du profil, plus un millier de tokens ecrits pour le seul usage de l'appel
     * suivant. Comptez le double de temps.
     */
    private suspend fun enDeuxTemps(
        offre: String,
        profile: Profile,
        settings: Settings,
        total: Int,
        emettre: (PipelineEvent) -> Unit,
    ): Produit {
        emettre(PipelineEvent.Etape(1, total, "Analyse et strategie",
            "Lecture de l'annonce et choix de l'angle de candidature"))

        val digest = digestAdapte(profile, settings, offre)
        // Une seule fenetre pour les deux etapes : en demander deux tailles
        // differentes rechargerait les 2,5 Go du modele entre elles.
        val contexte = contexteVoulu(
            settings,
            tokensPrompt = max(
                runtime.estimateTokens(Prompts.preparationSystem) +
                    runtime.estimateTokens(offre),
                runtime.estimateTokens(Prompts.redactionSystem) + TOKENS_RESUME_ETAPE1,
            ) + runtime.estimateTokens(digest.texte),
            budgetEcriture = max(MAX_PREPARATION, MAX_REDACTION),
        )

        val (prepModel, prepNoThink) = charger(AgentRole.ANALYSIS, settings, contexte)
        emettre(PipelineEvent.Modele(prepModel, detailMoteur(settings)))

        val preparation = runtime.generateJson(
            serializer = DossierPreparation.serializer(),
            system = Prompts.preparationSystem,
            user = Prompts.preparationUser(offre, digest.texte),
            params = GenerationParams.precise(maxTokens = MAX_PREPARATION),
            suppressReasoning = prepNoThink,
            etape = "Analyse et strategie",
            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
            onToken = { texte, n -> emettre(PipelineEvent.Jeton(texte, n)) },
            onTrace = { emettre(PipelineEvent.Mesure(it)) },
        )

        val analyse = preparation.analyse
        val langue = langueSortieDepuisAnalyse(settings, analyse)
        val strategie = remapperIdentifiants(preparation.strategie, digest)

        emettre(PipelineEvent.Etape(2, total, "Redaction",
            "Ecriture du CV et de la lettre de motivation"))
        val (redactionModel, redactionNoThink) = charger(AgentRole.WRITING, settings, contexte)
        emettre(PipelineEvent.Modele(redactionModel))

        val rediges = runtime.generateJson(
            serializer = DocumentsRediges.serializer(),
            system = Prompts.redactionSystem,
            user = Prompts.redactionUser(
                analyse, strategie, digest.texte,
                profile.identite.nomComplet, langue,
                profile.recherche.disponibilite, settings.cvUnePage,
            ),
            params = GenerationParams.writing(maxTokens = MAX_REDACTION),
            suppressReasoning = redactionNoThink,
            etape = "Redaction CV + lettre",
            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
            onToken = { texte, n -> emettre(PipelineEvent.Jeton(texte, n)) },
            onTrace = { emettre(PipelineEvent.Mesure(it)) },
        )

        return Produit(
            DossierComplet(analyse, strategie, rediges.cv, rediges.lettre), digest, langue,
        )
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
            val digest = digestAdapte(profile, settings, candidature.offreTexte)
            val langue = candidature.cv.langue.ifBlank { "fr" }
            val resultat = relireEtCorriger(
                profile, settings, candidature.analyse, candidature.strategie, digest,
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
        val (relectureModel, relectureNoThink) = charger(AgentRole.REVIEW, settings)
        emettre(PipelineEvent.Modele(relectureModel))

        val revueIa = runtime.generateJson(
            serializer = Review.serializer(),
            system = Prompts.relecteurSystem,
            user = Prompts.relecteurUser(
                analyse, digest.texte, cv.texteIntegral(), lettre.texteIntegral(),
            ),
            params = GenerationParams.precise(maxTokens = MAX_RELECTURE),
            suppressReasoning = relectureNoThink,
            etape = "Relecture critique",
            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
            onToken = { texte, n -> emettre(PipelineEvent.Jeton(texte, n)) },
            onTrace = { emettre(PipelineEvent.Mesure(it)) },
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
            val (correctionModel, correctionNoThink) = charger(AgentRole.WRITING, settings)
            emettre(PipelineEvent.Modele(correctionModel))

            repeat(min(settings.passesCorrection, 2)) { passe ->
                val cvCorrige = runCatching {
                    runtime.generateJson(
                        serializer = CvContent.serializer(),
                        system = Prompts.redacteurCvSystem,
                        user = Prompts.correctionCvUser(
                            analyse, strategie, digest.texte,
                            prettyJson.encodeToString(cv), revue, langue,
                        ),
                        params = GenerationParams.writing(maxTokens = MAX_CORRECTION_CV),
                        suppressReasoning = correctionNoThink,
                        etape = "Correction du CV",
                        onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
                        onToken = { texte, n -> emettre(PipelineEvent.Jeton(texte, n)) },
                        onTrace = { emettre(PipelineEvent.Mesure(it)) },
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
                            params = GenerationParams.writing(maxTokens = MAX_CORRECTION_LETTRE),
                            suppressReasoning = correctionNoThink,
                            etape = "Correction de la lettre",
                            onLecturePrompt = { lus, t, ms -> emettre(PipelineEvent.Lecture(lus, t, ms)) },
                            onToken = { texte, n -> emettre(PipelineEvent.Jeton(texte, n)) },
                            onTrace = { emettre(PipelineEvent.Mesure(it)) },
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
            val (model, noThink) = charger(AgentRole.EXPLAIN, settings)
            send(PipelineEvent.Modele(model))

            val offreUtile = tronquerOffre(offre) { trySend(PipelineEvent.Avertissement(it)) }
            val digest = if (profile.identite.nomComplet.isBlank()) ProfileDigest("", emptyMap())
            else digestAdapte(profile, settings, offreUtile)

            val explication = runtime.generateJson(
                serializer = JobExplanation.serializer(),
                system = Prompts.explicateurSystem,
                user = Prompts.explicateurUser(offreUtile, digest.texte),
                params = GenerationParams.explaining(maxTokens = MAX_EXPLICATION),
                suppressReasoning = noThink,
                etape = "Explication du poste",
                onLecturePrompt = { lus, total, ms -> trySend(PipelineEvent.Lecture(lus, total, ms)) },
                onToken = { texte, n -> trySend(PipelineEvent.Jeton(texte, n)) },
                onTrace = { trySend(PipelineEvent.Mesure(it)) },
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
     * Charge le modele affecte a [role]. Retourne son nom et s'il faut brider
     * son raisonnement.
     *
     * @param contexte fenetre a reserver. Null : garder celle du modele deja
     *   resident, ou le plafond des reglages. Chaque valeur differente force un
     *   rechargement complet des 2,5 Go du modele, alors les etapes d'une meme
     *   generation doivent toutes demander la meme.
     */
    private suspend fun charger(
        role: AgentRole,
        settings: Settings,
        contexte: Int? = null,
    ): Pair<String, Boolean> {
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

        val plafond = min(
            settings.tailleContexte,
            entree?.contextMax ?: settings.tailleContexte,
        )
        val voulu = contexte
            ?: runtime.currentModel?.takeIf { it.modelId == choisi.id }?.contextSize
            ?: plafond
        val effectif = min(plafond, voulu).coerceAtLeast(2048)

        runtime.ensureLoaded(
            modelId = choisi.id,
            filePath = choisi.file.absolutePath,
            contextSize = effectif,
            threads = settings.threads,
            gpuLayers = settings.couchesGpu,
            chargerEnMemoire = settings.chargerEnMemoire,
        )
        return choisi.displayName to (entree?.emitsReasoning ?: false)
    }

    /**
     * Fenetre de contexte a reserver, arrondie au multiple de 512 superieur.
     *
     * Elle n'est pas gratuite : llama.cpp alloue le cache d'attention pour la
     * fenetre entiere des le chargement, qu'on s'en serve ou non. Sur Qwen3 4B
     * c'est 144 Ko par token, soit 1,5 Go pour une fenetre de 10240 -- a cote
     * des 2,5 Go du modele, sur un telephone. Ce que l'on reserve en trop,
     * Android le reprend en evincant les pages du modele, et chaque token
     * demande alors d'aller les relire sur le stockage.
     */
    /** Le reglage effectif du moteur, pour que le bilan dise sur quoi il porte. */
    private fun detailMoteur(settings: Settings): String {
        val m = runtime.currentModel ?: return ""
        return "contexte ${m.contextSize} | ${settings.threads} threads en lecture, " +
            "${LlmRuntime.threadsEcriture(settings.threads)} en ecriture | poids " +
            (if (m.enMemoire) "copies en memoire" else "mappes depuis le fichier")
    }

    private fun contexteVoulu(settings: Settings, tokensPrompt: Int, budgetEcriture: Int): Int {
        val besoin = tokensPrompt + budgetEcriture + MARGE_PROMPT + LlmRuntime.MARGE_CONTEXTE
        val arrondi = ((besoin + 511) / 512) * 512
        return min(settings.tailleContexte, arrondi).coerceAtLeast(2048)
    }

    /**
     * Choisit entre profil detaille et profil resume selon la place que les
     * autres morceaux du prompt laissent reellement.
     *
     * L'ancienne regle -- la moitie du contexte -- ignorait le budget
     * d'ecriture. Or celui-ci n'est pas une limite souple : il s'additionne au
     * prompt dans la meme fenetre, et le moteur refuse de demarrer quand la
     * somme deborde. Avec un contexte de 6144, un profil fourni et un budget
     * d'ecriture large, l'etape de redaction echouait avant d'ecrire un seul
     * mot. On dimensionne donc sur l'etape la plus lourde des deux.
     */
    private suspend fun digestAdapte(
        profile: Profile,
        settings: Settings,
        offre: String,
    ): ProfileDigest {
        val contexte = runtime.currentModel?.contextSize ?: settings.tailleContexte

        val reserveAppelUnique = runtime.estimateTokens(Prompts.candidatureSystem) +
            runtime.estimateTokens(offre) + MAX_APPEL_UNIQUE
        val reserveAnalyse = runtime.estimateTokens(Prompts.preparationSystem) +
            runtime.estimateTokens(offre) + MAX_PREPARATION
        val reserveRedaction = runtime.estimateTokens(Prompts.redactionSystem) +
            TOKENS_RESUME_ETAPE1 + MAX_REDACTION
        val reserve = if (settings.analyseApprofondie) max(reserveAnalyse, reserveRedaction)
        else reserveAppelUnique
        val budget = contexte - reserve - LlmRuntime.MARGE_CONTEXTE - MARGE_PROMPT

        val complet = ProfileSerializer.digest(profile)
        if (budget > 0 && runtime.tokenCount(complet.texte) <= budget) return complet

        val court = ProfileSerializer.digestCourt(profile)
        Log.i(TAG, "Profil resume : budget $budget tokens dans un contexte de $contexte")
        return court
    }

    /**
     * Ramene une annonce demesuree a ce que le contexte peut absorber.
     *
     * Une offre collee depuis un site d'emploi traine souvent la page entiere :
     * menus, offres voisines, mentions legales. Sans garde-fou, tout cela est
     * lu par le modele -- du temps depense a la lecture, et de la place prise
     * au profil, qui est la seule source de faits.
     */
    private fun tronquerOffre(offre: String, avertir: (String) -> Unit): String {
        val propre = offre.trim()
        if (runtime.estimateTokens(propre) <= MAX_OFFRE) return propre
        val coupe = propre.take((MAX_OFFRE * 3.2).toInt())
        avertir(
            "Annonce tres longue (${propre.length} caracteres) : seules les " +
                "${coupe.length} premieres ont ete lues. Si l'essentiel de l'offre se " +
                "trouve plus bas, recollez uniquement la partie utile."
        )
        return coupe
    }

    private fun langueSortieDepuisAnalyse(settings: Settings, analyse: JobAnalysis): String =
        when (settings.langueSortie) {
            LangueSortie.FR -> "fr"
            LangueSortie.EN -> "en"
            LangueSortie.AUTO -> analyse.langue.lowercase().take(2).ifBlank { "fr" }
        }

    /**
     * Langue de sortie decidee avant d'ecrire.
     *
     * En un seul appel, la langue detectee par le modele arriverait trop tard :
     * elle sort en meme temps que le CV, pas avant. On tranche donc sur
     * l'annonce elle-meme, en comptant des mots outils que le francais n'a pas.
     * C'est grossier, mais une annonce est assez longue pour que ce soit sur, et
     * l'utilisateur peut toujours imposer la langue dans les reglages.
     */
    private fun langueSortie(settings: Settings, offre: String): String =
        when (settings.langueSortie) {
            LangueSortie.FR -> "fr"
            LangueSortie.EN -> "en"
            LangueSortie.AUTO -> {
                val mots = offre.lowercase().split(Regex("[^a-z]+"))
                val anglais = mots.count { it in MOTS_ANGLAIS }
                val francais = mots.count { it in MOTS_FRANCAIS }
                if (anglais > francais) "en" else "fr"
            }
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

    private companion object {
        const val TAG = "Orchestrator"

        // ---------------------------------------------------------------
        // Budgets d'ecriture.
        //
        // Ce ne sont pas des garde-fous theoriques. Un budget sert deux fois :
        // il borne ce que le modele peut ecrire avant qu'on l'arrete, et il est
        // reserve dans la fenetre de contexte, donc retire au prompt. Trop
        // large, il coute du temps quand le modele part en boucle, et de la
        // place quand il se tient bien.
        //
        // Les valeurs sont celles que la sortie demande reellement :
        // - une lettre de 250 a 330 mots pese 400 a 550 tokens en francais ;
        // - un CV d'une page en JSON, 700 a 900 ;
        // - l'analyse et la strategie, 800 a 1000.
        // La generation s'arrete de toute facon des que l'objet JSON se
        // referme : ces plafonds ne servent qu'aux reponses qui derapent.
        // ---------------------------------------------------------------
        /**
         * Appel unique : une courte analyse (200), un angle (120), le CV (700)
         * et la lettre (550), plus la structure JSON.
         */
        const val MAX_APPEL_UNIQUE = 2000
        const val MAX_PREPARATION = 1200
        const val MAX_REDACTION = 1800
        const val MAX_RELECTURE = 1000
        const val MAX_CORRECTION_CV = 1400
        const val MAX_CORRECTION_LETTRE = 900
        const val MAX_EXPLICATION = 1200

        /** Taille typique du resume d'annonce + strategie injecte a l'etape 2. */
        const val TOKENS_RESUME_ETAPE1 = 900

        /** Ecart admis entre l'estimation par caracteres et la vraie tokenisation. */
        const val MARGE_PROMPT = 200

        /** Au-dela, une annonce collee contient surtout la page du site. */
        const val MAX_OFFRE = 1600

        // Mots outils tres frequents, et absents de l'autre langue. Ils suffisent
        // a trancher sur un texte de la longueur d'une annonce.
        val MOTS_ANGLAIS = setOf(
            "the", "and", "with", "for", "you", "your", "will", "our", "we", "are",
            "have", "this", "that", "from", "who", "team", "role", "skills",
        )
        val MOTS_FRANCAIS = setOf(
            "le", "la", "les", "des", "vous", "nous", "votre", "notre", "et", "un",
            "une", "pour", "avec", "dans", "sur", "poste", "profil", "equipe",
        )
    }
}
