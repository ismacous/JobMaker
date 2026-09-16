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

    /** Dialecte OpenAI : Groq, OpenRouter, et la plupart des passerelles. */
    fun corpsOpenAi(modele: String, messages: List<ChatMessage>, params: GenerationParams): String {
        // Les API OpenAI-compatibles exigent le mot "json" quelque part dans la
        // conversation quand on demande le mode JSON, et repondent 400 sinon.
        val mentionneJson = messages.any { it.content.contains("json", ignoreCase = true) }
        val obj = buildJsonObject {
            put("model", modele)
            put("stream", true)
            put("temperature", params.temperature)
            put("top_p", params.topP)
            put("max_tokens", params.maxTokens)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
                if (params.sortieJson && !mentionneJson) {
                    addJsonObject {
                        put("role", "system")
                        put("content", "Repond uniquement par un objet json valide.")
                    }
                }
            }
            if (params.sortieJson) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }
        return obj.toString()
    }

    /** Dialecte Google : le systeme se declare a part, les roles different. */
    fun corpsGemini(messages: List<ChatMessage>, params: GenerationParams): String {
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
                if (params.sortieJson) put("responseMimeType", "application/json")
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
    ): String = when (fournisseur.dialecte) {
        DialecteCloud.OPENAI -> corpsOpenAi(modele, messages, params)
        DialecteCloud.GEMINI -> corpsGemini(messages, params)
    }

    // -----------------------------------------------------------------------
    // Lecture du flux SSE
    // -----------------------------------------------------------------------

    /** Marqueur de fin du dialecte OpenAI. */
    const val FIN_FLUX = "[DONE]"

    /**
     * Extrait le texte d'une ligne "data:" du flux.
     *
     * Retourne null quand la ligne ne porte pas de texte (keep-alive, metadonnees
     * d'usage, bloc de raisonnement). Leve [ErreurCloud] quand le flux transporte
     * une erreur applicative, ce que font les deux dialectes au lieu de couper la
     * connexion.
     */
    fun morceau(dialecte: DialecteCloud, data: String): String? {
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
        return runCatching { texte(dialecte, racine) }.getOrNull()
    }

    private fun texte(dialecte: DialecteCloud, racine: JsonObject): String? = when (dialecte) {
        DialecteCloud.OPENAI -> racine["choices"]
            ?.let { it.jsonArray.firstOrNull()?.jsonObject }
            ?.let { choix ->
                choix["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contenuOuNull()
                    // Certaines passerelles renvoient la reponse complete au
                    // lieu d'un delta sur le dernier evenement.
                    ?: choix["message"]?.jsonObject?.get("content")
                        ?.jsonPrimitive?.contenuOuNull()
            }

        DialecteCloud.GEMINI -> racine["candidates"]
            ?.let { it.jsonArray.firstOrNull()?.jsonObject }
            ?.let { candidat ->
                candidat["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.mapNotNull { p -> p.jsonObject["text"]?.jsonPrimitive?.contenuOuNull() }
                    ?.joinToString("")
                    ?.takeIf { it.isNotEmpty() }
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
                    listOf("whisper", "tts", "guard", "embed", "moderation", "vision-ocr")
                        .any { bas.contains(it) }
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

        429 -> "Quota gratuit atteint chez ${fournisseur.nom} (${fournisseur.quota}). " +
            "Reessayez dans quelques minutes, changez de fournisseur, ou repassez " +
            "sur le moteur de l'appareil."

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
