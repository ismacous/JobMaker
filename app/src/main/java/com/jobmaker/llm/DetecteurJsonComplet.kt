package com.jobmaker.llm

/**
 * Detecte, au fil des tokens, l'instant ou la premiere valeur JSON de premier
 * niveau se referme.
 *
 * Raison d'etre : quand on demande un objet JSON a un petit modele, il lui
 * arrive de le refermer correctement puis de continuer -- une phrase de
 * politesse, une reprise du meme objet, une liste de remarques. Rien n'arrete
 * cela : le jeton de fin n'est pas emis, et la generation continue jusqu'au
 * budget de tokens. Sur un telephone qui ecrit une dizaine de tokens par
 * seconde, un millier de tokens inutiles coutent deux minutes par etape.
 *
 * On ne peut pas s'en remettre a une simple recherche de "}" : le texte des
 * champs en contient. Il faut suivre la profondeur des accolades en ignorant
 * ce qui se trouve entre guillemets, exactement comme le fait
 * [com.jobmaker.agents.JsonRepair] sur la reponse complete -- mais ici de
 * maniere incrementale, morceau par morceau, sans jamais relire le debut.
 *
 * Deux precautions rendent la coupure sure :
 *
 * 1. Les blocs de raisonnement des modeles hybrides sont ignores. Un modele
 *    qui reflechit ecrit tres souvent un brouillon du JSON dans son <think> ;
 *    compter ses accolades ferait couper la generation avant la vraie reponse.
 * 2. Tous les doutes penchent du meme cote : quand l'etat devient incertain,
 *    le detecteur redemarre a zero. Au pire on ne coupe pas et le budget de
 *    tokens fait son office ; jamais on ne coupe une reponse en cours.
 */
internal class DetecteurJsonComplet {

    private var demarre = false
    private var profondeur = 0
    private var dansChaine = false
    private var echappe = false
    private var dansRaisonnement = false

    /** Derniers caracteres vus, pour reconnaitre une balise coupee entre deux tokens. */
    private val fenetre = StringBuilder()

    /** Vrai des que la valeur de premier niveau a ete refermee. */
    var termine: Boolean = false
        private set

    /**
     * Avale le morceau de texte suivant. Retourne vrai quand la valeur de
     * premier niveau vient d'etre refermee (ou l'a deja ete).
     */
    fun avaler(morceau: String): Boolean {
        if (termine) return true
        for (c in morceau) {
            if (baliseRencontree(c)) continue
            if (dansRaisonnement) continue
            when {
                echappe -> echappe = false
                dansChaine && c == '\\' -> echappe = true
                c == '"' -> dansChaine = !dansChaine
                dansChaine -> Unit
                c == '{' || c == '[' -> {
                    demarre = true
                    profondeur++
                }
                c == '}' || c == ']' -> {
                    if (demarre) {
                        profondeur--
                        if (profondeur <= 0) {
                            termine = true
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * Met a jour la fenetre glissante et bascule l'etat "dans un bloc de
     * raisonnement". Retourne vrai quand le caractere vient de completer une
     * balise, auquel cas il ne doit pas etre compte comme du JSON.
     */
    private fun baliseRencontree(c: Char): Boolean {
        fenetre.append(c)
        if (fenetre.length > LONGUEUR_FENETRE) fenetre.delete(0, fenetre.length - LONGUEUR_FENETRE)
        if (c != '>') return false

        for (tag in TAGS) {
            if (fenetre.endsWith("<$tag>")) {
                dansRaisonnement = true
                reinitialiser()
                return true
            }
            if (fenetre.endsWith("</$tag>")) {
                dansRaisonnement = false
                reinitialiser()
                return true
            }
        }
        return false
    }

    private fun reinitialiser() {
        demarre = false
        profondeur = 0
        dansChaine = false
        echappe = false
    }

    private companion object {
        /** Memes balises que celles nettoyees par JsonRepair.stripReasoning. */
        val TAGS = listOf("think", "thinking", "reasoning", "reflection")

        /** Longueur de la plus longue balise fermante, "</reflection>". */
        const val LONGUEUR_FENETRE = 13
    }
}
