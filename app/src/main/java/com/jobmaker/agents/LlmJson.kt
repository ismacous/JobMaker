package com.jobmaker.agents

import android.util.Log
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import com.jobmaker.llm.LlmRuntime
import com.jobmaker.llm.TraceAppel
import kotlinx.serialization.KSerializer

private const val TAG = "LlmJson"

/**
 * Interrupteur de raisonnement des modeles hybrides. Qwen3 (et plusieurs
 * derives) reconnaissent ce marqueur et repondent directement, ce qui evite de
 * bruler des centaines de tokens de reflexion la ou on veut juste du JSON.
 */
private const val NO_THINK = "/no_think"

/**
 * Demande une reponse JSON au modele et la deserialise.
 *
 * En cas d'echec de parsing, on renvoie au modele sa propre sortie avec la
 * consigne de la corriger, plutot que de relancer tout le calcul : la
 * correction porte sur quelques dizaines de tokens au lieu de plusieurs
 * milliers.
 */
suspend fun <T> LlmRuntime.generateJson(
    serializer: KSerializer<T>,
    system: String,
    user: String,
    params: GenerationParams,
    suppressReasoning: Boolean,
    etape: String = "",
    onLecturePrompt: ((Int, Int, Long) -> Unit)? = null,
    onToken: ((String, Int) -> Unit)? = null,
    onTrace: ((TraceAppel) -> Unit)? = null,
): T {
    val userText = if (suppressReasoning) "$user\n\n$NO_THINK" else user

    val messages = listOf(ChatMessage.system(system), ChatMessage.user(userText))

    // Le budget d'ecriture ne peut pas etre decide a l'aveugle : il s'ajoute au
    // prompt dans la meme fenetre de contexte, et le moteur refuse de demarrer
    // si la somme deborde. On le ramene donc a la place reellement libre.
    val (effectifs, tokensPrompt) = ajusterBudget(messages, params)
        ?: throw LlmException(
            "Le texte a lire remplit deja la fenetre de contexte du modele : il ne " +
                "reste pas de place pour ecrire la reponse. Raccourcissez l'offre collee, " +
                "allegez votre profil, ou augmentez la taille de contexte dans Reglages."
        )
    if (effectifs.maxTokens < params.maxTokens) {
        Log.i(TAG, "Budget ramene de ${params.maxTokens} a ${effectifs.maxTokens} " +
            "(prompt $tokensPrompt tokens)")
    }

    val raw = complete(messages, effectifs, etape, onLecturePrompt, onToken, onTrace)

    parseOrNull(serializer, raw)?.let { return it }

    Log.w(TAG, "JSON invalide, tentative de reparation par le modele")

    val repairPrompt = buildString {
        appendLine("La reponse ci-dessous devait etre un objet JSON valide, mais elle ne l'est pas.")
        appendLine("Renvoie EXACTEMENT le meme contenu, corrige, en JSON strictement valide.")
        appendLine("Aucun texte avant ni apres, aucune balise de code.")
        appendLine()
        appendLine("--- REPONSE A CORRIGER ---")
        appendLine(raw.take(6000))
        if (suppressReasoning) { appendLine(); append(NO_THINK) }
    }

    val messagesReparation = listOf(
        ChatMessage.system("Tu es un correcteur de JSON. Tu ne reponds que par du JSON valide."),
        ChatMessage.user(repairPrompt),
    )
    val paramsReparation = GenerationParams.precise(maxTokens = effectifs.maxTokens)
    val (reparationAjustee, _) = ajusterBudget(messagesReparation, paramsReparation)
        ?: throw LlmException(
            "Le modele n'a pas produit de JSON exploitable, et sa reponse est trop " +
                "longue pour etre corrigee. Essayez un autre modele, ou raccourcissez l'offre."
        )

    val repaired = complete(
        messages = messagesReparation,
        params = reparationAjustee,
        etape = if (etape.isBlank()) "reparation JSON" else "$etape (reparation)",
        onToken = onToken,
        onTrace = onTrace,
    )

    return parseOrNull(serializer, repaired)
        ?: throw LlmException(
            "Le modele n'a pas produit de JSON exploitable. " +
                "Essayez un modele plus gros (reglages > modeles), ou raccourcissez l'offre."
        )
}

private fun <T> parseOrNull(serializer: KSerializer<T>, raw: String): T? {
    val cleaned = JsonRepair.clean(raw) ?: return null
    return runCatching { JsonRepair.lenient.decodeFromString(serializer, cleaned) }
        .onFailure { Log.w(TAG, "Deserialisation impossible : ${it.message}") }
        .getOrNull()
}

/** Variante texte libre, pour les sorties destinees a etre lues telles quelles. */
suspend fun LlmRuntime.generateProse(
    system: String,
    user: String,
    params: GenerationParams,
    suppressReasoning: Boolean,
    etape: String = "",
    onLecturePrompt: ((Int, Int, Long) -> Unit)? = null,
    onToken: ((String, Int) -> Unit)? = null,
    onTrace: ((TraceAppel) -> Unit)? = null,
): String {
    val userText = if (suppressReasoning) "$user\n\n$NO_THINK" else user
    val messages = listOf(ChatMessage.system(system), ChatMessage.user(userText))
    val (effectifs, _) = ajusterBudget(messages, params.copy(arretJsonComplet = false))
        ?: throw LlmException(
            "Le texte a lire remplit deja la fenetre de contexte du modele. " +
                "Raccourcissez-le, ou augmentez la taille de contexte dans Reglages."
        )
    val raw = complete(messages, effectifs, etape, onLecturePrompt, onToken, onTrace)
    return JsonRepair.cleanProse(raw)
}
