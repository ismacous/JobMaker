package com.jobmaker.llm.cloud

import android.util.Log
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Panne d'un moteur distant.
 *
 * [repliPossible] distingue ce qui se resout en changeant de moteur (pas de
 * reseau, quota epuise, service en panne) de ce qui se resout en changeant de
 * reglage (cle invalide, modele inexistant). Le premier cas justifie de
 * basculer sur le modele local, le second non : basculer masquerait une erreur
 * de configuration que l'utilisateur doit corriger.
 */
class PanneCloud(message: String, val repliPossible: Boolean) : LlmException(message)

/**
 * Appels aux API distantes, en flux.
 *
 * Ce que cette classe ne fait jamais :
 *  - journaliser une cle, un en-tete d'autorisation ou un corps de requete.
 *    Les journaux Android sont lisibles par l'utilisateur et parfois joints a
 *    un rapport de bug ; une cle qui y passe est une cle a revoquer.
 *  - accepter une URL qui n'est pas celle du fournisseur choisi, ni du HTTP en
 *    clair. Le fichier network_security_config.xml verrouille deja le clair au
 *    niveau du systeme ; ceci en est la ceinture.
 */
class ClientCloud(private val http: OkHttpClient = clientParDefaut()) {

    private val typeJson = "application/json; charset=utf-8".toMediaType()

    /**
     * Genere une reponse et la renvoie complete, en appelant [onToken] au fil
     * de l'eau.
     */
    suspend fun completer(
        fournisseur: FournisseurCloud,
        modele: String,
        cle: String,
        messages: List<ChatMessage>,
        params: GenerationParams,
        onToken: ((String) -> Unit)? = null,
        /** Prevenu quand l'appel patiente le temps que le quota se renouvelle. */
        onAttente: ((secondes: Int) -> Unit)? = null,
        /**
         * Patienter sur un quota epuise plutot que de rendre la main.
         *
         * A false quand un autre fournisseur peut prendre le relais : basculer
         * est instantane, attendre coute une minute. On ne patiente qu'en bout
         * de chaine, ou l'alternative est le petit modele du telephone.
         */
        attendreSurQuota: Boolean = true,
    ): String {
        val modeleRetenu = modele.ifBlank { fournisseur.modeleParDefaut }
        var extras = true
        var tentative = 0
        var attentes = 0

        // Boucle sans sortie normale : on quitte par un return ou une
        // exception. Le compilateur le sait, il ne reclame pas de valeur finale.
        while (true) {
            tentative++
            try {
                return withContext(Dispatchers.IO) {
                    flux(fournisseur, modeleRetenu, cle, messages, params, extras, onToken)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReponseInvalide) {
                // Un modele qui ne connait ni le mode JSON natif ni la bride de
                // raisonnement : on retente avec le corps minimal. Le pipeline
                // sait deja rattraper un JSON approximatif.
                if (e.code == 400 && extras) {
                    Log.i(TAG, "Reglage refuse par $modeleRetenu, nouvelle tentative sans")
                    extras = false
                    continue
                }
                if (e.code in 500..599 && tentative <= MAX_TENTATIVES) {
                    delay(1000L * tentative * tentative)
                    continue
                }

                // Quota par minute epuise. La fenetre se reouvre en moins d'une
                // minute et le fournisseur dit exactement quand : attendre est
                // presque toujours meilleur que de basculer sur le modele du
                // telephone, qui ecrit moins bien et met dix minutes. On ne
                // renonce que si l'attente depasse le raisonnable, ou si elle
                // se repete trop -- la, c'est le quota du jour, pas la minute.
                if (e.code == 429 && attendreSurQuota && attentes < MAX_ATTENTES) {
                    val secondes = (e.attenteS ?: ATTENTE_PAR_DEFAUT).coerceAtMost(ATTENTE_MAX)
                    if (e.attenteS == null || e.attenteS <= ATTENTE_MAX) {
                        attentes++
                        Log.i(TAG, "Quota atteint, attente de $secondes s")
                        onAttente?.invoke(secondes)
                        delay(secondes * 1000L)
                        continue
                    }
                }

                throw PanneCloud(
                    RequetesCloud.messageErreur(fournisseur, e.code, e.detail, modeleRetenu),
                    repliPossible = e.code == 429 || e.code in 500..599,
                )
            } catch (e: IOException) {
                if (tentative <= MAX_TENTATIVES) {
                    delay(1000L * tentative * tentative)
                    continue
                }
                throw PanneCloud(
                    "Connexion a ${fournisseur.nom} impossible. Verifiez le reseau du " +
                        "telephone, puis reessayez.",
                    repliPossible = true,
                )
            }
        }
    }

    /** Liste les modeles que cette cle peut reellement utiliser. */
    suspend fun listerModeles(
        fournisseur: FournisseurCloud,
        cle: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val requete = Request.Builder()
            .url(verifierUrl(fournisseur, fournisseur.urlModeles()))
            .headers(entetes(fournisseur, cle, flux = false))
            .get()
            .build()
        http.newCall(requete).execute().use { reponse ->
            val corps = reponse.body?.string().orEmpty()
            if (!reponse.isSuccessful) {
                throw PanneCloud(
                    RequetesCloud.messageErreur(
                        fournisseur, reponse.code, RequetesCloud.detailErreur(corps), "",
                    ),
                    repliPossible = false,
                )
            }
            RequetesCloud.modeles(fournisseur.dialecte, corps)
        }
    }

    // -----------------------------------------------------------------------
    // Interne
    // -----------------------------------------------------------------------

    private suspend fun flux(
        fournisseur: FournisseurCloud,
        modele: String,
        cle: String,
        messages: List<ChatMessage>,
        params: GenerationParams,
        extras: Boolean,
        onToken: ((String) -> Unit)?,
    ): String {
        val corps = RequetesCloud.corps(fournisseur, modele, messages, params, extras)
        val requete = Request.Builder()
            .url(verifierUrl(fournisseur, fournisseur.urlGeneration(modele)))
            .headers(entetes(fournisseur, cle, flux = true))
            .post(corps.toRequestBody(typeJson))
            .build()

        val appel = http.newCall(requete)
        val debut = System.currentTimeMillis()

        try {
            appel.execute().use { reponse ->
                if (!reponse.isSuccessful) {
                    // Le corps ne se lit qu'une fois : il porte le message et,
                    // chez Google, l'attente a respecter.
                    val corpsErreur = reponse.body?.string().orEmpty()
                    val attente = attenteDemandee(reponse)
                        ?: RequetesCloud.attenteDepuisCorps(corpsErreur)
                    val detail = RequetesCloud.detailErreur(corpsErreur)
                    Log.w(TAG, "${fournisseur.hote} a repondu ${reponse.code}")
                    throw ReponseInvalide(reponse.code, masquer(detail, cle), attente)
                }

                val source = reponse.body?.source()
                    ?: throw ReponseInvalide(502, "reponse vide")

                val sortie = StringBuilder()
                var reflexion = 0
                while (true) {
                    if (!currentCoroutineContext().isActive) {
                        appel.cancel()
                        return sortie.toString()
                    }
                    val ligne = source.readUtf8Line() ?: break
                    if (!ligne.startsWith("data:")) continue
                    val data = ligne.removePrefix("data:")
                    if (data.trim() == RequetesCloud.FIN_FLUX) break
                    val morceau = try {
                        RequetesCloud.morceau(fournisseur.dialecte, data)
                    } catch (e: ErreurCloud) {
                        throw PanneCloud(
                            "${fournisseur.nom} a interrompu la generation : " +
                                masquer(e.message.orEmpty(), cle),
                            repliPossible = true,
                        )
                    } ?: continue

                    // Le raisonnement est montre a l'ecran pour temoigner de
                    // l'activite, mais n'entre jamais dans le resultat : c'est
                    // un brouillon, pas une reponse.
                    morceau.raisonnement?.let {
                        reflexion += it.length
                        onToken?.invoke(it)
                    }
                    morceau.texte?.let {
                        sortie.append(it)
                        onToken?.invoke(it)
                    }
                }

                val duree = System.currentTimeMillis() - debut
                Log.i(TAG, "${fournisseur.hote} : ${sortie.length} caracteres en $duree ms")

                if (sortie.isBlank()) {
                    // Un modele a raisonnement qui a tout depense a reflechir :
                    // le diagnostic est different, et la solution aussi.
                    throw PanneCloud(
                        if (reflexion > 0) {
                            "Le modele \"$modele\" a epuise son budget de tokens en " +
                                "reflexion sans rien ecrire. Choisissez un modele sans " +
                                "raisonnement, ou reessayez : la demande etait trop courte " +
                                "pour lui."
                        } else {
                            "${fournisseur.nom} n'a rien renvoye. Le modele \"$modele\" a " +
                                "peut-etre bloque la demande ; essayez-en un autre."
                        },
                        repliPossible = true,
                    )
                }
                return sortie.toString()
            }
        } catch (e: IllegalStateException) {
            // OkHttp leve cela quand on lit un corps deja consomme.
            throw PanneCloud("Reponse illisible de ${fournisseur.nom}.", repliPossible = true)
        }
    }

    private fun entetes(fournisseur: FournisseurCloud, cle: String, flux: Boolean): Headers {
        val b = Headers.Builder()
            .add("Accept", if (flux) "text/event-stream" else "application/json")
            .add("User-Agent", AGENT)
        when (fournisseur.dialecte) {
            DialecteCloud.OPENAI -> b.add("Authorization", "Bearer ${cle.trim()}")
            // Google accepte la cle en en-tete : elle ne se retrouve donc ni
            // dans l'URL, ni dans les journaux de proxy, ni dans un referer.
            DialecteCloud.GEMINI -> b.add("x-goog-api-key", cle.trim())
        }
        if (fournisseur == FournisseurCloud.OPENROUTER) {
            // Demande par OpenRouter pour identifier l'application appelante.
            // Aucune donnee personnelle : juste le nom du projet.
            b.add("X-Title", "JobMaker")
        }
        return b.build()
    }

    /** Refuse toute URL qui n'est pas du HTTPS sur l'hote du fournisseur. */
    private fun verifierUrl(fournisseur: FournisseurCloud, url: String): String {
        require(url.startsWith("https://${fournisseur.hote}/")) {
            "URL inattendue pour ${fournisseur.nom}"
        }
        return url
    }

    /** Retire la cle d'un texte destine a l'ecran ou aux journaux. */
    private fun masquer(texte: String, cle: String): String =
        if (cle.length < 8) texte else texte.replace(cle.trim(), "***")

    private class ReponseInvalide(
        val code: Int,
        val detail: String,
        /** Secondes d'attente demandees par l'en-tete Retry-After, si present. */
        val attenteS: Int? = null,
    ) : Exception(detail)

    /**
     * Lit l'attente demandee par le fournisseur.
     *
     * Groq la donne en secondes, parfois fractionnaires ("7.66"). C'est
     * l'information la plus utile d'un 429 : elle dit exactement quand la
     * fenetre se reouvre, au lieu de laisser deviner.
     */
    private fun attenteDemandee(reponse: okhttp3.Response): Int? {
        val brut = reponse.header("retry-after")
            ?: reponse.header("x-ratelimit-reset-tokens")
            ?: reponse.header("x-ratelimit-reset-requests")
            ?: return null
        val nombre = brut.trim().removeSuffix("s").toDoubleOrNull() ?: return null
        return kotlin.math.ceil(nombre).toInt().coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "ClientCloud"
        private const val MAX_TENTATIVES = 2

        /**
         * Le quota par minute se renouvelle en 60 secondes. Au-dela, ce n'est
         * plus la fenetre glissante qui bloque mais le quota journalier :
         * attendre ne servirait a rien.
         */
        private const val ATTENTE_MAX = 75
        private const val ATTENTE_PAR_DEFAUT = 30

        /** Quatre etapes dans le pipeline, donc au plus une attente par etape. */
        private const val MAX_ATTENTES = 4

        private const val AGENT = "JobMaker/1.0 (Android)"

        fun clientParDefaut(): OkHttpClient = OkHttpClient.Builder()
            // Pas d'intercepteur de journalisation : voir l'en-tete de classe.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
