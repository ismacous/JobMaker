package com.jobmaker.agents

import android.util.Log
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.LlmException
import com.jobmaker.llm.LlmRuntime
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
    onLecturePrompt: ((Int, Int, Long) -> Unit)? = null,
    onToken: ((String) -> Unit)? = null,
): T {
    val userText = if (suppressReasoning) "$user\n\n$NO_THINK" else user

    val messages = listOf(ChatMessage.system(system), ChatMessage.user(userText))
    val raw = complete(messages, params, onLecturePrompt, onToken)

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

    val repaired = complete(
        messages = listOf(
            ChatMessage.system("Tu es un correcteur de JSON. Tu ne reponds que par du JSON valide."),
            ChatMessage.user(repairPrompt),
        ),
        params = GenerationParams.precise(maxTokens = params.maxTokens),
        onToken = onToken,
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
    onLecturePrompt: ((Int, Int, Long) -> Unit)? = null,
    onToken: ((String) -> Unit)? = null,
): String {
    val userText = if (suppressReasoning) "$user\n\n$NO_THINK" else user
    val raw = complete(
        messages = listOf(ChatMessage.system(system), ChatMessage.user(userText)),
        params = params,
        onLecturePrompt = onLecturePrompt,
        onToken = onToken,
    )
    return JsonRepair.cleanProse(raw)
}
