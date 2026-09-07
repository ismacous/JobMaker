package com.jobmaker.agents

import kotlinx.serialization.json.Json

/**
 * Recuperation du JSON produit par un modele local.
 *
 * Un modele de 4 milliards de parametres qui tourne sur un telephone ne rend
 * pas du JSON aussi propre qu'une API cloud : il ajoute une phrase
 * d'introduction, entoure sa reponse de balises de code, oublie une accolade
 * fermante, laisse une virgule en trop, ou glisse un bloc de raisonnement.
 * Plutot que de rejeter la reponse et de tout recalculer (plusieurs minutes),
 * on la repare.
 */
object JsonRepair {

    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Supprime les blocs de raisonnement des modeles hybrides (Qwen3, DeepSeek-R1
     * et derives). Un bloc non ferme, parce que la generation a ete coupee, est
     * supprime jusqu'a la fin du texte.
     */
    fun stripReasoning(raw: String): String {
        var text = raw
        for (tag in listOf("think", "thinking", "reasoning", "reflection")) {
            text = text.replace(
                Regex("<$tag>.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
                "",
            )
            // Bloc ouvert et jamais ferme : on jette la queue.
            val open = Regex("<$tag>", RegexOption.IGNORE_CASE).find(text)
            if (open != null && !text.contains("</$tag>", ignoreCase = true)) {
                text = text.substring(0, open.range.first)
            }
            text = text.replace(Regex("</?$tag>", RegexOption.IGNORE_CASE), "")
        }
        return text.trim()
    }

    /** Retire les balises de code markdown autour de la reponse. */
    private fun stripFences(text: String): String {
        val fence = Regex("```(?:json|JSON)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val match = fence.find(text)
        return (match?.groupValues?.get(1) ?: text).trim()
    }

    /**
     * Isole le premier objet (ou tableau) JSON complet du texte, en comptant les
     * accolades et en ignorant celles qui sont dans une chaine.
     */
    fun extractJsonBlock(text: String): String? {
        val opens = charArrayOf('{', '[')
        val start = text.indexOfFirst { it in opens }
        if (start < 0) return null
        val openChar = text[start]
        val closeChar = if (openChar == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escaped = false
        var lastClose = -1

        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == openChar -> depth++
                c == closeChar -> {
                    depth--
                    if (depth == 0) { lastClose = i; break }
                }
            }
        }

        if (lastClose > start) return text.substring(start, lastClose + 1)

        // Generation tronquee : on referme nous-memes ce qui reste ouvert.
        val partial = text.substring(start)
        return closeUnbalanced(partial)
    }

    /**
     * Ferme un JSON tronque : on coupe apres le dernier element manifestement
     * complet, puis on rajoute les fermetures manquantes. Recupere ainsi la
     * majeure partie d'une reponse coupee par la limite de tokens.
     */
    private fun closeUnbalanced(partial: String): String {
        // 1. Derniere virgule situee hors chaine et a faible profondeur : au-dela,
        //    le contenu est un element incomplet qu'on prefere jeter.
        var lastSafe = -1
        run {
            val stack = ArrayDeque<Char>()
            var inString = false
            var escaped = false
            for (i in partial.indices) {
                val c = partial[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' || c == '[' -> stack.addLast(c)
                    c == '}' || c == ']' -> if (stack.isNotEmpty()) stack.removeLast()
                    c == ',' -> if (stack.size <= 2) lastSafe = i
                }
            }
        }

        var body = if (lastSafe > 0) partial.substring(0, lastSafe) else partial.trimEnd()

        // 2. L'etat (chaine ouverte, imbrications restantes) doit etre recalcule
        //    SUR LE CORPS CONSERVE : celui du texte complet decrit une position
        //    qu'on vient justement de supprimer.
        val need = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in body) {
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> need.addLast('}')
                c == '[' -> need.addLast(']')
                c == '}' || c == ']' -> if (need.isNotEmpty()) need.removeLast()
            }
        }

        if (inString) body += "\""
        body = body.trimEnd().trimEnd(',').trimEnd()
        while (need.isNotEmpty()) body += need.removeLast()
        return body
    }

    /** Corrections de surface les plus fréquentes. */
    private fun patch(json: String): String = json
        // Guillemets typographiques que certains modeles produisent en francais.
        .replace('“', '"').replace('”', '"')
        .replace('‘', '\'').replace('’', '\'')
        // Virgules orphelines avant une fermeture.
        .replace(Regex(",\\s*([}\\]])"), "$1")
        // Commentaires, que le JSON n'accepte pas.
        .replace(Regex("^\\s*//.*$", RegexOption.MULTILINE), "")

    /** Chaine complete : raisonnement, balises, extraction, corrections. */
    fun clean(raw: String): String? {
        val noReasoning = stripReasoning(raw)
        val noFence = stripFences(noReasoning)
        val block = extractJsonBlock(noFence) ?: return null
        return patch(block)
    }

    /** Nettoyage d'un texte libre (pas de JSON attendu). */
    fun cleanProse(raw: String): String =
        stripReasoning(raw)
            .replace(Regex("^```[a-zA-Z]*\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .trim()
}
