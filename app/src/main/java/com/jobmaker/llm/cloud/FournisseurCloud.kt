package com.jobmaker.llm.cloud

/**
 * Forme des requetes attendue par le fournisseur.
 *
 * Groq et OpenRouter parlent le dialecte OpenAI (/chat/completions, messages,
 * SSE avec "choices[].delta.content"). Google a son propre schema. Deux
 * dialectes suffisent a couvrir les trois offres gratuites qui tiennent la
 * route aujourd'hui.
 */
enum class DialecteCloud { OPENAI, GEMINI }

/**
 * Une API distante gratuite capable de rediger un CV.
 *
 * Aucune cle n'est stockee ici : ce sont des metadonnees publiques. La cle de
 * l'utilisateur vit chiffree dans [com.jobmaker.data.prefs.CoffreCles] et n'est
 * passee qu'au moment de l'appel.
 *
 * [modeleParDefaut] vieillit vite -- les fournisseurs retirent des modeles tous
 * les quelques mois. C'est voulu : l'ecran "Moteur d'IA" interroge l'API pour
 * lister les modeles reellement disponibles, et le champ reste libre. Le defaut
 * n'est qu'un point de depart raisonnable.
 */
enum class FournisseurCloud(
    val nom: String,
    val dialecte: DialecteCloud,
    val hote: String,
    val modeleParDefaut: String,
    /** Page ou l'on cree une cle gratuite. */
    val urlCle: String,
    /** Debut attendu de la cle. Vide si le fournisseur n'en impose pas. */
    val prefixeCle: String,
    val quota: String,
    val resume: String,
    /** Ce que le fournisseur fait des donnees envoyees, sur son offre gratuite. */
    val politiqueDonnees: String,
) {
    GROQ(
        nom = "Groq",
        dialecte = DialecteCloud.OPENAI,
        hote = "api.groq.com",
        modeleParDefaut = "openai/gpt-oss-120b",
        urlCle = "https://console.groq.com/keys",
        prefixeCle = "gsk_",
        quota = "14 400 requetes par jour, mais surtout 6 000 a 30 000 tokens par " +
            "minute selon le modele -- c'est cette limite-la que l'on atteint, les " +
            "gros modeles etant les plus serres",
        resume = "Le plus rapide : quelques secondes pour un CV complet. " +
            "Inscription par e-mail, sans carte bancaire.",
        politiqueDonnees = "Groq annonce ne pas entrainer ses modeles sur le contenu " +
            "envoye par l'API.",
    ),
    GEMINI(
        nom = "Google Gemini",
        dialecte = DialecteCloud.GEMINI,
        hote = "generativelanguage.googleapis.com",
        modeleParDefaut = "gemini-flash-latest",
        urlCle = "https://aistudio.google.com/apikey",
        prefixeCle = "AIza",
        quota = "environ 15 requetes par minute, 1 500 par jour et 1 million de " +
            "tokens par minute -- la plus confortable des trois sur les gros textes",
        resume = "La meilleure qualite de redaction des trois, et le seul a " +
            "garantir un JSON conforme au schema demande.",
        politiqueDonnees = "Attention : sur l'offre GRATUITE, Google se reserve le droit " +
            "de faire relire les echanges par des humains et de s'en servir pour " +
            "ameliorer ses modeles. Votre CV et vos offres passeraient par la. " +
            "Si cela vous derange, choisissez Groq ou le moteur sur l'appareil.",
    ),
    OPENROUTER(
        nom = "OpenRouter",
        dialecte = DialecteCloud.OPENAI,
        hote = "openrouter.ai",
        modeleParDefaut = "meta-llama/llama-3.3-70b-instruct:free",
        urlCle = "https://openrouter.ai/keys",
        prefixeCle = "sk-or-",
        quota = "variable selon le modele, souvent quelques dizaines de requetes " +
            "par jour",
        resume = "Un seul compte donne acces aux modeles gratuits de plusieurs " +
            "editeurs. Pratique en secours quand un quota est atteint ailleurs.",
        politiqueDonnees = "Depend du modele choisi. Les modeles marques \":free\" sont " +
            "souvent fournis en echange de l'usage des donnees : verifiez la fiche " +
            "du modele avant d'y envoyer votre profil.",
    );

    /** URL de generation. [modele] n'est utilise que par le dialecte Gemini. */
    fun urlGeneration(modele: String): String = when (this) {
        GROQ -> "https://api.groq.com/openai/v1/chat/completions"
        OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/" +
            "${modele.trim()}:streamGenerateContent?alt=sse"
    }

    /** URL de la liste des modeles disponibles pour cette cle. */
    fun urlModeles(): String = when (this) {
        GROQ -> "https://api.groq.com/openai/v1/models"
        OPENROUTER -> "https://openrouter.ai/api/v1/models"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
    }

    /**
     * Verification de forme, volontairement permissive : elle attrape le
     * copier-coller rate (espaces, guillemets, cle d'un autre fournisseur) sans
     * rejeter une cle valide dont le prefixe aurait change.
     */
    fun formatPlausible(cle: String): Boolean {
        val c = cle.trim()
        if (c.length < 20) return false
        if (c.any { it.isWhitespace() }) return false
        return true
    }

    companion object {
        fun parNom(valeur: String?): FournisseurCloud =
            entries.firstOrNull { it.name == valeur } ?: GROQ
    }
}
