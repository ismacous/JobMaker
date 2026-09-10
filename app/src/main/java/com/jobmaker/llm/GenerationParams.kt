package com.jobmaker.llm

/**
 * Reglages d'echantillonnage pour un appel.
 *
 * Les etapes qui doivent produire du JSON exploitable tournent en quasi
 * deterministe ([precise]) : un petit modele qui "cree" pendant qu'il structure
 * casse le JSON. Les etapes de redaction ont besoin d'un peu de liberte
 * ([writing]) sinon les phrases se repetent d'une candidature a l'autre.
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
    val stopSequences: List<String> = FINS_DE_TOUR,
    /**
     * Couper des que la premiere valeur JSON de premier niveau est refermee.
     *
     * A activer pour toute etape dont la sortie est un objet JSON : c'est le
     * seul garde-fou fiable contre un modele qui repond juste, puis continue.
     */
    val arretJsonComplet: Boolean = false,
) {
    companion object {
        /**
         * Marqueurs de fin de tour des gabarits de conversation courants.
         *
         * Le moteur s'arrete deja sur le jeton de fin declare par le modele.
         * Ces chaines ne servent que quand ce jeton manque ou que l'on est
         * retombe sur le gabarit ChatML generique : le modele ecrit alors le
         * marqueur en clair au lieu de l'emettre comme jeton special, et sans
         * cette liste il repartirait pour un tour entier de dialogue invente.
         */
        val FINS_DE_TOUR = listOf("<|im_end|>", "<|endoftext|>", "<|eot_id|>", "<end_of_turn>")

        /**
         * Extraction / structuration : on veut la meme sortie a chaque fois.
         *
         * Aucune penalite de repetition. Sur du JSON elle est nuisible : les
         * tokens les plus repetes d'une reponse structuree sont justement ceux
         * qui la rendent valide (guillemets, deux-points, virgules, accolades,
         * noms de champs). Les penaliser pousse le modele a s'en ecarter, donc
         * a produire du JSON casse -- qu'il faut ensuite faire reparer par un
         * second appel complet.
         */
        fun precise(maxTokens: Int = 1000) = GenerationParams(
            maxTokens = maxTokens,
            temperature = 0.15f,
            topP = 0.85f,
            topK = 20,
            repeatPenalty = 1.0f,
            arretJsonComplet = true,
        )

        /**
         * Redaction de CV / lettre, rendue en JSON : un peu de variete, pas de delire.
         *
         * La penalite reste faible et sa fenetre courte. Elle sert a empecher
         * une phrase de tourner en boucle, pas a diversifier le vocabulaire --
         * c'est la graine, tiree au hasard a chaque appel, qui change les
         * formulations d'une candidature a l'autre. Une penalite forte sur une
         * sortie JSON s'en prendrait d'abord aux guillemets et aux virgules.
         */
        fun writing(maxTokens: Int = 1800) = GenerationParams(
            maxTokens = maxTokens,
            temperature = 0.5f,
            topP = 0.92f,
            topK = 40,
            repeatPenalty = 1.05f,
            repeatLastN = 128,
            arretJsonComplet = true,
        )

        /** Explication pedagogique : ton naturel, sortie JSON egalement. */
        fun explaining(maxTokens: Int = 1200) = GenerationParams(
            maxTokens = maxTokens,
            temperature = 0.4f,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.05f,
            repeatLastN = 128,
            arretJsonComplet = true,
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
