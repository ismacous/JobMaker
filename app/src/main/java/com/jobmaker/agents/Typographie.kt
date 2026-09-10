package com.jobmaker.agents

/**
 * Nettoie la ponctuation qui signe une redaction par IA.
 *
 * Un recruteur ne lit pas un CV en cherchant des preuves ; il en repere une
 * sans la chercher. Le tiret cadratin en est une : les modeles de langue, dont
 * l'essentiel de l'entrainement est anglophone, l'emploient comme incise
 * plusieurs fois par paragraphe. En francais courant, presque personne ne
 * l'utilise -- ni au clavier, ou il n'a pas de touche, ni dans une lettre de
 * motivation, ou la virgule, les deux-points et les parentheses suffisent.
 *
 * La consigne donnee au modele ne suffit pas : elle tient trois paragraphes,
 * puis l'habitude reprend. La correction est donc mecanique, appliquee apres
 * coup, et seulement au texte que le modele a redige -- jamais aux champs que
 * l'application compose elle-meme, ou le tiret separe deux informations et ne
 * ponctue rien.
 */
object Typographie {

    /**
     * Tirets employes comme ponctuation : cadratin, demi-cadratin, et le trait
     * d'union quand il est isole entre deux espaces. Le trait d'union colle a
     * ses mots (« Aix-en-Provence », « sous-traitance ») n'est jamais touche.
     */
    private val incise = Regex("\\s*[\u2014\u2013]\\s*|\\s+-\\s+")

    /** Une virgule suivie d'une autre ponctuation : reste de la substitution. */
    private val ponctuationDoublee = Regex(",\\s*([,;:.!?])")

    private val espacesMultiples = Regex("[ \\t]{2,}")

    /**
     * Remplace les incises par une virgule et repare la ponctuation qui en
     * decoule. A n'appliquer qu'a de la prose redigee par le modele.
     */
    fun assainir(texte: String): String {
        if (texte.isBlank()) return texte
        var out = incise.replace(texte, ", ")
        out = ponctuationDoublee.replace(out, "$1")
        out = espacesMultiples.replace(out, " ")
        // Une incise en fin de phrase laisse une virgule orpheline.
        out = out.replace(Regex(",\\s*$"), "")
        return out.trim()
    }

    /** Variante liste : chaque entree est assainie, les vides sont retirees. */
    fun assainir(textes: List<String>): List<String> =
        textes.map { assainir(it) }.filter { it.isNotBlank() }
}
