package com.jobmaker.llm.cloud

import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.GenerationParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Fabrication des requetes et lecture des reponses des API distantes.
 *
 * Tout est ici en fonctions pures, sans reseau : c'est la partie qui se teste
 * sur un poste de developpement, et celle ou se cachent les erreurs de format
 * qui coutent une heure a diagnostiquer sur telephone.
 */
object RequetesCloud {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // -----------------------------------------------------------------------
    // Corps des requetes
    // -----------------------------------------------------------------------

    /**
     * Modeles qui reflechissent avant de repondre.
     *
     * Leur raisonnement est facture sur le meme budget que la reponse : sans
     * bride, un gpt-oss peut depenser mille tokens a reflechir puis n'avoir
     * plus de quoi ecrire, et l'appel renvoie une reponse vide. "low" garde le
     * benefice du raisonnement sur une offre mal redigee sans y engloutir le
     * budget -- et accelere la generation au passage.
     */
    private fun raisonne(modele: String): Boolean =
        modele.contains("gpt-oss", ignoreCase = true)

    /**
     * Dialecte OpenAI : Groq, OpenRouter, et la plupart des passerelles.
     *
     * [extras] a false produit le corps minimal, sans mode JSON ni bride de
     * raisonnement : c'est la deuxieme tentative, apres qu'un modele a refuse
     * l'un de ces reglages par un 400.
     */
    fun corpsOpenAi(
        modele: String,
        messages: List<ChatMessage>,
        params: GenerationParams,
        extras: Boolean = true,
    ): String {
        // Les API OpenAI-compatibles exigent le mot "json" quelque part dans la
        // conversation quand on demande le mode JSON, et repondent 400 sinon.
        val mentionneJson = messages.any { it.content.contains("json", ignoreCase = true) }
        val modeJson = params.sortieJson && extras
        val obj = buildJsonObject {
            put("model", modele)
            put("stream", true)
            put("temperature", params.temperature)
            put("top_p", params.topP)
            put("max_tokens", params.maxTokens)
            if (extras && raisonne(modele)) put("reasoning_effort", "low")
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
                if (modeJson && !mentionneJson) {
                    addJsonObject {
                        put("role", "system")
                        put("content", "Repond uniquement par un objet json valide.")
                    }
                }
            }
            if (modeJson) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }
        return obj.toString()
    }

    /** Dialecte Google : le systeme se declare a part, les roles different. */
    fun corpsGemini(
        messages: List<ChatMessage>,
        params: GenerationParams,
        extras: Boolean = true,
    ): String {
        val systeme = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        val echanges = messages.filter { it.role != "system" }
        val obj = buildJsonObject {
            if (systeme.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systeme) } }
                }
            }
            putJsonArray("contents") {
                echanges.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", m.content) } }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", params.temperature)
                put("topP", params.topP)
                put("topK", params.topK)
                put("maxOutputTokens", params.maxTokens)
                if (params.sortieJson && extras) put("responseMimeType", "application/json")
            }
            // Les filtres de securite de Google bloquent parfois une annonce
            // parfaitement anodine (secteur medical, securite privee, armee).
            // On les met au minimum autorise : ce sont des offres d'emploi.
            putJsonArray("safetySettings") {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT",
                ).forEach { categorie ->
                    addJsonObject {
                        put("category", categorie)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    }
                }
            }
        }
        return obj.toString()
    }

    fun corps(
        fournisseur: FournisseurCloud,
        modele: String,
        messages: List<ChatMessage>,
        params: GenerationParams,
        extras: Boolean = true,
    ): String = when (fournisseur.dialecte) {
        DialecteCloud.OPENAI -> corpsOpenAi(modele, messages, params, extras)
        DialecteCloud.GEMINI -> corpsGemini(messages, params, extras)
    }

    // -----------------------------------------------------------------------
    // Lecture du flux SSE
    // -----------------------------------------------------------------------

    /** Marqueur de fin du dialecte OpenAI. */
    const val FIN_FLUX = "[DONE]"

    /**
     * Un evenement du flux.
     *
     * Les modeles a raisonnement separent ce qu'ils se disent a eux-memes de ce
     * qu'ils repondent. Le premier ne doit jamais atterrir dans un CV, mais il
     * a son utilite : affiche a l'ecran, il montre que la generation avance au
     * lieu de laisser croire que tout est fige.
     */
    data class MorceauFlux(
        /** Ce qui compte : la reponse. */
        val texte: String? = null,
        /** Ce que le modele se dit avant de repondre. Affiche, jamais conserve. */
        val raisonnement: String? = null,
    )

    /**
     * Extrait le texte d'une ligne "data:" du flux.
     *
     * Retourne null quand la ligne ne porte pas de texte (keep-alive, metadonnees
     * d'usage, bloc de raisonnement). Leve [ErreurCloud] quand le flux transporte
     * une erreur applicative, ce que font les deux dialectes au lieu de couper la
     * connexion.
     */
    fun morceau(dialecte: DialecteCloud, data: String): MorceauFlux? {
        val brut = data.trim()
        if (brut.isEmpty() || brut == FIN_FLUX) return null
        val racine = runCatching { json.parseToJsonElement(brut).jsonObject }.getOrNull()
            ?: return null

        racine["error"]?.let { erreur ->
            val message = runCatching { erreur.jsonObject["message"]?.jsonPrimitive?.content }
                .getOrNull() ?: erreur.toString()
            throw ErreurCloud(message)
        }

        // Au-dela de l'erreur applicative, aucune malformation ne doit
        // interrompre une generation en cours : une ligne qu'on ne sait pas
        // lire est une ligne qu'on ignore.
        val m = runCatching { extraire(dialecte, racine) }.getOrNull() ?: return null
        return if (m.texte == null && m.raisonnement == null) null else m
    }

    private fun extraire(dialecte: DialecteCloud, racine: JsonObject): MorceauFlux? = when (dialecte) {
        DialecteCloud.OPENAI -> racine["choices"]
            ?.let { it.jsonArray.firstOrNull()?.jsonObject }
            ?.let { choix ->
                val delta = choix["delta"]?.jsonObject
                MorceauFlux(
                    texte = delta?.get("content")?.jsonPrimitive?.contenuOuNull()
                        // Certaines passerelles renvoient la reponse complete au
                        // lieu d'un delta sur le dernier evenement.
                        ?: choix["message"]?.jsonObject?.get("content")
                            ?.jsonPrimitive?.contenuOuNull(),
                    // Groq place le raisonnement des gpt-oss dans son propre
                    // champ ; d'autres passerelles le nomment "reasoning_content".
                    raisonnement = delta?.get("reasoning")?.jsonPrimitive?.contenuOuNull()
                        ?: delta?.get("reasoning_content")?.jsonPrimitive?.contenuOuNull(),
                )
            }

        DialecteCloud.GEMINI -> racine["candidates"]
            ?.let { it.jsonArray.firstOrNull()?.jsonObject }
            ?.let { candidat ->
                val parties = candidat["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?: return@let null
                // Google marque ses parties de reflexion d'un drapeau "thought".
                val (pensees, reponse) = parties.partition { p ->
                    runCatching {
                        p.jsonObject["thought"]?.jsonPrimitive?.content == "true"
                    }.getOrDefault(false)
                }
                fun texteDe(l: List<JsonElement>) = l
                    .mapNotNull { p -> p.jsonObject["text"]?.jsonPrimitive?.contenuOuNull() }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
                MorceauFlux(texte = texteDe(reponse), raisonnement = texteDe(pensees))
            }
    }

    private fun JsonPrimitive.contenuOuNull(): String? =
        if (this is JsonNull) null else content.takeIf { it.isNotEmpty() }

    // -----------------------------------------------------------------------
    // Liste des modeles
    // -----------------------------------------------------------------------

    /**
     * Identifiants de modeles utilisables, extraits de la reponse de l'API.
     *
     * Les catalogues bougent vite -- un modele phare disparait tous les six mois.
     * Plutot que de figer une liste dans l'application, on demande au fournisseur
     * ce qu'il sert aujourd'hui.
     */
    fun modeles(dialecte: DialecteCloud, corps: String): List<String> {
        val racine = runCatching { json.parseToJsonElement(corps).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (dialecte) {
            DialecteCloud.OPENAI -> (racine["data"]?.jsonArray ?: emptyList<JsonElement>())
                .mapNotNull {
                    runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                }
                // Les modeles audio, image et garde-fous ne redigent pas de CV.
                .filterNot { id ->
                    val bas = id.lowercase()
                    listOf(
                        "whisper", "tts", "guard", "embed", "moderation", "vision-ocr",
                        "orpheus", "playai", "parakeet",
                    ).any { bas.contains(it) }
                }

            DialecteCloud.GEMINI -> (racine["models"]?.jsonArray ?: emptyList<JsonElement>())
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .filter { m ->
                    m["supportedGenerationMethods"]?.jsonArray
                        ?.any { it.jsonPrimitive.content == "generateContent" } ?: true
                }
                .mapNotNull { m -> m["name"]?.jsonPrimitive?.content?.removePrefix("models/") }
                .filterNot { id ->
                    val bas = id.lowercase()
                    listOf("embedding", "aqa", "imagen", "veo", "tts").any { bas.contains(it) }
                }
        }.distinct().sorted()
    }

    // -----------------------------------------------------------------------
    // Erreurs
    // -----------------------------------------------------------------------

    /**
     * Traduit un code HTTP en phrase utile, avec la marche a suivre.
     *
     * Les messages bruts des fournisseurs sont en anglais et parlent de
     * "rate limit exceeded for RPD on model X" : inexploitable pour quelqu'un
     * qui cherche juste a postuler.
     */
    fun messageErreur(
        fournisseur: FournisseurCloud,
        code: Int,
        detail: String,
        modele: String,
    ): String = when (code) {
        400 -> "${fournisseur.nom} a refuse la requete" +
            (detail.takeIf { it.isNotBlank() }?.let { " : $it" } ?: ".") +
            " Le modele \"$modele\" ne sait peut-etre pas repondre en JSON : " +
            "essayez-en un autre dans Reglages > Moteur d'IA."

        401, 403 -> "Cle refusee par ${fournisseur.nom}. Verifiez-la dans " +
            "Reglages > Moteur d'IA, ou creez-en une nouvelle sur ${fournisseur.urlCle}."

        404 -> "Le modele \"$modele\" n'existe plus chez ${fournisseur.nom}. " +
            "Ouvrez Reglages > Moteur d'IA et rafraichissez la liste des modeles."

        413 -> "L'offre collee est trop longue pour ce modele. Gardez l'essentiel " +
            "de l'annonce (missions, profil recherche, entreprise)."

        429 -> "Quota gratuit de ${fournisseur.nom} epuise, et l'attente n'a pas suffi : " +
            "c'est probablement la limite du jour. Trois sorties : un modele plus " +
            "petit (son quota par minute est plus large), un autre fournisseur, ou " +
            "le moteur de l'appareil. Detail du quota : ${fournisseur.quota}."

        in 500..599 -> "${fournisseur.nom} est momentanement indisponible (erreur $code). " +
            "Reessayez dans un instant."

        else -> "${fournisseur.nom} a repondu $code" +
            (detail.takeIf { it.isNotBlank() }?.let { " : $it" } ?: ".")
    }

    /** Message applicatif d'une reponse d'erreur, sans le bruit du JSON. */
    fun detailErreur(corps: String): String {
        val racine = runCatching { json.parseToJsonElement(corps).jsonObject }.getOrNull()
            ?: return corps.take(200)
        val erreur = racine["error"] ?: return corps.take(200)
        val message = runCatching { erreur.jsonObject["message"]?.jsonPrimitive?.content }
            .getOrNull()
            ?: runCatching { erreur.jsonPrimitive.content }.getOrNull()
            ?: erreur.toString()
        return message.take(300)
    }
}

/** Erreur applicative transportee par le flux lui-meme. */
class ErreurCloud(message: String) : Exception(message)
