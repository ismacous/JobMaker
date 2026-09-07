package com.jobmaker.llm

/**
 * Reglages d'echantillonnage pour un appel.
 *
 * Les etapes qui doivent produire du JSON exploitable tournent en quasi
 * deterministe ([PRECISE]) : un petit modele qui "cree" pendant qu'il structure
 * casse le JSON. Les etapes de redaction ont besoin d'un peu de liberte
 * ([WRITING]) sinon les phrases se repetent d'une candidature a l'autre.
 */
data class GenerationParams(
    val maxTokens: Int = 1024,
    val temperature: Float = 0.4f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.08f,
    val repeatLastN: Int = 256,
    val seed: Int = -1,
    /** Sequences qui coupent la generation des qu'elles apparaissent. */
    val stopSequences: List<String> = emptyList(),
) {
    companion object {
        /** Extraction / structuration : on veut la meme sortie a chaque fois. */
        fun precise(maxTokens: Int = 1400) = GenerationParams(
            maxTokens = maxTokens,
            temperature = 0.15f,
            topP = 0.85f,
            topK = 20,
            repeatPenalty = 1.05f,
        )

        /** Redaction de CV / lettre : un peu de variete, mais pas de delire. */
        fun writing(maxTokens: Int = 1800) = GenerationParams(
            maxTokens = maxTokens,
            temperature = 0.55f,
            topP = 0.92f,
            topK = 50,
            repeatPenalty = 1.12f,
        )

        /** Explication pedagogique : ton naturel. */
        fun explaining(maxTokens: Int = 1600) = GenerationParams(
            maxTokens = maxTokens,
            temperature = 0.4f,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.1f,
        )
    }
}

data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}
