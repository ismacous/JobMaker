package com.jobmaker

import com.jobmaker.llm.ChaineMoteurs
import com.jobmaker.llm.ChatMessage
import com.jobmaker.llm.ConfigMoteur
import com.jobmaker.llm.GenerationParams
import com.jobmaker.llm.cloud.DialecteCloud
import com.jobmaker.llm.cloud.ErreurCloud
import com.jobmaker.llm.cloud.FournisseurCloud
import com.jobmaker.llm.cloud.QuotaObserve
import com.jobmaker.llm.cloud.RequetesCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le format des requetes et la lecture du flux sont la ou une erreur coute le
 * plus cher : elle ne se voit qu'une fois l'APK installe sur le telephone, avec
 * une vraie cle, et se manifeste par un 400 illisible. Ces cas reproduisent les
 * reponses reellement servies par les deux dialectes.
 */
class CloudTest {

    private val messages = listOf(
        ChatMessage.system("Tu rediges des CV."),
        ChatMessage.user("Voici l'offre."),
    )

    // -----------------------------------------------------------------------
    // Corps des requetes
    // -----------------------------------------------------------------------

    @Test
    fun `le dialecte openai transmet modele, flux et messages`() {
        val corps = RequetesCloud.corpsOpenAi(
            "openai/gpt-oss-120b", messages, GenerationParams.writing(maxTokens = 900),
        )
        assertTrue(corps.contains("\"model\":\"openai/gpt-oss-120b\""))
        assertTrue(corps.contains("\"stream\":true"))
        assertTrue(corps.contains("\"max_tokens\":900"))
        assertTrue(corps.contains("\"role\":\"system\""))
        assertTrue(corps.contains("Voici l'offre."))
        // Pas de mode JSON demande : pas de response_format impose.
        assertFalse(corps.contains("response_format"))
    }

    @Test
    fun `le mode json ajoute le format et la mention exigee par l'api`() {
        val corps = RequetesCloud.corpsOpenAi(
            "modele", messages, GenerationParams.precise().copy(sortieJson = true),
        )
        assertTrue(corps.contains("\"response_format\":{\"type\":\"json_object\"}"))
        // Les API OpenAI-compatibles refusent le mode JSON si le mot n'apparait
        // nulle part dans la conversation : on l'ajoute alors nous-memes.
        assertTrue(corps.contains("objet json valide"))
    }

    @Test
    fun `la mention json deja presente n'est pas dupliquee`() {
        val avecJson = listOf(
            ChatMessage.system("Reponds en JSON strict."),
            ChatMessage.user("Voici l'offre."),
        )
        val corps = RequetesCloud.corpsOpenAi(
            "modele", avecJson, GenerationParams.precise().copy(sortieJson = true),
        )
        assertFalse(corps.contains("objet json valide"))
    }

    @Test
    fun `le dialecte gemini sort le systeme des echanges`() {
        val corps = RequetesCloud.corpsGemini(
            messages + ChatMessage.assistant("Deja ecrit."),
            GenerationParams.writing().copy(sortieJson = true),
        )
        assertTrue(corps.contains("\"systemInstruction\""))
        assertTrue(corps.contains("Tu rediges des CV."))
        // Google nomme "model" ce que tout le monde appelle "assistant".
        assertTrue(corps.contains("\"role\":\"model\""))
        assertFalse(corps.contains("\"role\":\"system\""))
        assertTrue(corps.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(corps.contains("BLOCK_ONLY_HIGH"))
    }

    // -----------------------------------------------------------------------
    // Lecture du flux
    // -----------------------------------------------------------------------

    @Test
    fun `un delta openai rend son texte`() {
        val ligne = """{"choices":[{"delta":{"content":"Magasin"},"index":0}]}"""
        assertEquals("Magasin", RequetesCloud.morceau(DialecteCloud.OPENAI, ligne)?.texte)
    }

    @Test
    fun `le raisonnement est separe de la reponse, jamais melange`() {
        // gpt-oss chez Groq : ce que le modele se dit arrive dans son propre
        // champ. Le laisser passer pour du texte mettrait son brouillon dans
        // le CV ; l'ignorer donnerait un ecran fige pendant qu'il reflechit.
        val pense = """{"choices":[{"delta":{"reasoning":"L'offre demande un CAP."}}]}"""
        val m = RequetesCloud.morceau(DialecteCloud.OPENAI, pense)
        assertNull(m?.texte)
        assertEquals("L'offre demande un CAP.", m?.raisonnement)
    }

    @Test
    fun `gemini distingue ses parties de reflexion`() {
        val ligne = """
            {"candidates":[{"content":{"parts":[
              {"text":"Je dois lister les missions.","thought":true},
              {"text":"Magasinier cariste"}]}}]}
        """.trimIndent()
        val m = RequetesCloud.morceau(DialecteCloud.GEMINI, ligne)
        assertEquals("Magasinier cariste", m?.texte)
        assertEquals("Je dois lister les missions.", m?.raisonnement)
    }

    @Test
    fun `les modeles a raisonnement sont brides, les autres intacts`() {
        // Sans bride, gpt-oss depense son budget a reflechir et rend une
        // reponse vide : c'est exactement ce qui cassait le bouton "Tester".
        val avec = RequetesCloud.corpsOpenAi(
            "openai/gpt-oss-120b", messages, GenerationParams.precise(),
        )
        assertTrue(avec.contains("\"reasoning_effort\":\"low\""))

        val sans = RequetesCloud.corpsOpenAi(
            "qwen/qwen3.8-27b", messages, GenerationParams.precise(),
        )
        assertFalse(sans.contains("reasoning_effort"))
    }

    @Test
    fun `le corps minimal abandonne tout ce qu'un modele peut refuser`() {
        // Deuxieme tentative apres un 400 : ni mode JSON, ni bride.
        val corps = RequetesCloud.corpsOpenAi(
            "openai/gpt-oss-120b",
            messages,
            GenerationParams.precise().copy(sortieJson = true),
            extras = false,
        )
        assertFalse(corps.contains("reasoning_effort"))
        assertFalse(corps.contains("response_format"))
        assertTrue(corps.contains("Voici l'offre."))
    }

    @Test
    fun `les evenements sans texte sont ignores`() {
        // Premier evenement : le role arrive seul, sans contenu.
        assertNull(
            RequetesCloud.morceau(
                DialecteCloud.OPENAI,
                """{"choices":[{"delta":{"role":"assistant"},"index":0}]}""",
            )
        )
        // Dernier evenement : usage et raison d'arret, toujours sans contenu.
        assertNull(
            RequetesCloud.morceau(
                DialecteCloud.OPENAI,
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":12}}""",
            )
        )
        assertNull(RequetesCloud.morceau(DialecteCloud.OPENAI, " [DONE]"))
        assertNull(RequetesCloud.morceau(DialecteCloud.OPENAI, "   "))
    }

    @Test
    fun `gemini recolle les morceaux d'un meme evenement`() {
        val ligne = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"Bonjour "},{"text":"Madame"}]}}]}
        """.trimIndent()
        assertEquals("Bonjour Madame", RequetesCloud.morceau(DialecteCloud.GEMINI, ligne)?.texte)
    }

    @Test(expected = ErreurCloud::class)
    fun `une erreur transportee par le flux remonte`() {
        // Les deux fournisseurs signalent ainsi un quota epuise en cours de
        // generation, sans couper la connexion.
        RequetesCloud.morceau(
            DialecteCloud.OPENAI,
            """{"error":{"message":"rate limit exceeded","type":"rate_limit"}}""",
        )
    }

    @Test
    fun `une ligne illisible ne fait pas tomber la generation`() {
        assertNull(RequetesCloud.morceau(DialecteCloud.OPENAI, "{ceci n'est pas du json"))
    }

    // -----------------------------------------------------------------------
    // Catalogue de modeles
    // -----------------------------------------------------------------------

    @Test
    fun `la liste openai ecarte ce qui ne redige pas`() {
        val corps = """
            {"data":[{"id":"openai/gpt-oss-120b"},{"id":"whisper-large-v3"},
                     {"id":"meta-llama/llama-guard-4-12b"},{"id":"qwen/qwen3-32b"},
                     {"id":"canopylabs/orpheus-v1-english"}]}
        """.trimIndent()
        assertEquals(
            listOf("openai/gpt-oss-120b", "qwen/qwen3-32b"),
            RequetesCloud.modeles(DialecteCloud.OPENAI, corps),
        )
    }

    @Test
    fun `la liste gemini retire le prefixe et les modeles qui ne generent pas`() {
        val corps = """
            {"models":[
              {"name":"models/gemini-flash-latest",
               "supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004",
               "supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-pro-latest",
               "supportedGenerationMethods":["generateContent","countTokens"]}]}
        """.trimIndent()
        assertEquals(
            listOf("gemini-flash-latest", "gemini-pro-latest"),
            RequetesCloud.modeles(DialecteCloud.GEMINI, corps),
        )
    }

    @Test
    fun `une reponse inattendue donne une liste vide plutot qu'une exception`() {
        assertTrue(RequetesCloud.modeles(DialecteCloud.OPENAI, "<html>502</html>").isEmpty())
    }

    // -----------------------------------------------------------------------
    // Messages d'erreur
    // -----------------------------------------------------------------------

    @Test
    fun `chaque code http donne une consigne exploitable`() {
        val cle = RequetesCloud.messageErreur(FournisseurCloud.GROQ, 401, "", "m")
        assertTrue(cle.contains("Cle refusee"))
        assertTrue(cle.contains(FournisseurCloud.GROQ.urlCle))

        val quota = RequetesCloud.messageErreur(FournisseurCloud.GEMINI, 429, "", "m")
        assertTrue(quota.contains("Quota gratuit"))
        assertTrue(quota.contains(FournisseurCloud.GEMINI.quota))

        val absent = RequetesCloud.messageErreur(FournisseurCloud.GROQ, 404, "", "llama-3.1")
        assertTrue(absent.contains("llama-3.1"))
        assertTrue(absent.contains("rafraichissez la liste"))
    }

    @Test
    fun `l'attente demandee par google est lue dans le corps de l'erreur`() {
        // Google ne met pas d'en-tete Retry-After : l'attente exacte est dans
        // error.details, sous un RetryInfo. Sans la lire, on patienterait une
        // duree arbitraire la ou l'API donne la reponse.
        val corps = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"27s"}]}}
        """.trimIndent()
        assertEquals(27, RequetesCloud.attenteDepuisCorps(corps))
    }

    @Test
    fun `une attente fractionnaire est arrondie vers le haut`() {
        val corps = """
            {"error":{"details":[
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7.4s"}]}}
        """.trimIndent()
        assertEquals(8, RequetesCloud.attenteDepuisCorps(corps))
    }

    @Test
    fun `une erreur sans attente indiquee ne fait pas deviner`() {
        assertNull(RequetesCloud.attenteDepuisCorps("""{"error":{"message":"nope"}}"""))
        assertNull(RequetesCloud.attenteDepuisCorps("<html>502</html>"))
    }

    @Test
    fun `le detail d'erreur est extrait du json du fournisseur`() {
        val corps = """{"error":{"message":"model decommissioned","code":"model_not_found"}}"""
        assertEquals("model decommissioned", RequetesCloud.detailErreur(corps))
    }

    @Test
    fun `une cle vide ou bricolee est refusee avant tout appel reseau`() {
        val f = FournisseurCloud.GROQ
        assertFalse(f.formatPlausible(""))
        assertFalse(f.formatPlausible("trop-court"))
        assertFalse(f.formatPlausible("gsk_ avec un espace au milieu"))
        assertTrue(f.formatPlausible("gsk_0123456789abcdef0123456789abcdef"))
    }

    // -----------------------------------------------------------------------
    // Chaine de secours
    // -----------------------------------------------------------------------

    @Test
    fun `le fournisseur choisi passe en premier, les autres derriere`() {
        val chaine = ChaineMoteurs.fournisseurs(
            actif = FournisseurCloud.GEMINI,
            avecCle = setOf(FournisseurCloud.GROQ, FournisseurCloud.GEMINI),
            enchainer = true,
        )
        assertEquals(listOf(FournisseurCloud.GEMINI, FournisseurCloud.GROQ), chaine)
    }

    @Test
    fun `un fournisseur sans cle ne peut pas servir de secours`() {
        val chaine = ChaineMoteurs.fournisseurs(
            actif = FournisseurCloud.GROQ,
            avecCle = setOf(FournisseurCloud.GROQ),
            enchainer = true,
        )
        assertEquals(listOf(FournisseurCloud.GROQ), chaine)
    }

    @Test
    fun `sans enchainement, seul le fournisseur choisi est essaye`() {
        val chaine = ChaineMoteurs.fournisseurs(
            actif = FournisseurCloud.GROQ,
            avecCle = FournisseurCloud.entries.toSet(),
            enchainer = false,
        )
        assertEquals(listOf(FournisseurCloud.GROQ), chaine)
    }

    @Test
    fun `un fournisseur choisi sans cle laisse la place a celui qui en a une`() {
        // Cas reel : on selectionne Gemini, on oublie d'enregistrer sa cle.
        // Mieux vaut generer avec Groq que ne rien generer du tout.
        val chaine = ChaineMoteurs.fournisseurs(
            actif = FournisseurCloud.GEMINI,
            avecCle = setOf(FournisseurCloud.GROQ),
            enchainer = true,
        )
        assertEquals(listOf(FournisseurCloud.GROQ), chaine)
    }

    @Test
    fun `aucune cle nulle part donne une chaine vide`() {
        assertTrue(
            ChaineMoteurs.fournisseurs(
                actif = FournisseurCloud.GROQ,
                avecCle = emptySet(),
                enchainer = true,
            ).isEmpty()
        )
    }

    @Test
    fun `le budget de sortie ne gonfle que pour les modeles a raisonnement`() {
        // Les tokens produits comptent dans le quota par minute : gonfler le
        // budget pour un modele qui n'en a pas besoin rapproche le mur.
        assertTrue(RequetesCloud.raisonne("openai/gpt-oss-120b"))
        assertFalse(RequetesCloud.raisonne("qwen/qwen3.8-27b"))
        assertFalse(RequetesCloud.raisonne("gemini-flash-latest"))
    }

    @Test
    fun `le quota annonce par le fournisseur se resume, ou se tait`() {
        val annonce = QuotaObserve(
            requetesRestantes = "9 998", requetesLimite = "14 400",
            tokensRestants = "2 100", tokensLimite = "8 000",
        )
        val resume = annonce.resume()
        assertNotNull(resume)
        assertTrue(resume!!.contains("8 000"))
        assertTrue(resume.contains("14 400"))

        // Google n'envoie pas ces en-tetes : mieux vaut ne rien afficher qu'un
        // chiffre invente.
        assertNull(QuotaObserve().resume())
    }

    @Test
    fun `le modele leger reste facultatif`() {
        // Vide par defaut : le meme modele aux trois etapes, comme avant.
        val sans = ConfigMoteur()
        assertEquals("", sans.modeleLegerPour(FournisseurCloud.GROQ))

        val avec = ConfigMoteur(
            modelesCloud = mapOf(FournisseurCloud.GROQ to "openai/gpt-oss-120b"),
            modelesLegers = mapOf(FournisseurCloud.GROQ to "openai/gpt-oss-20b"),
        )
        assertEquals("openai/gpt-oss-120b", avec.modelePour(FournisseurCloud.GROQ))
        assertEquals("openai/gpt-oss-20b", avec.modeleLegerPour(FournisseurCloud.GROQ))
        // Le reglage est par fournisseur : rien ne fuit vers les autres.
        assertEquals("", avec.modeleLegerPour(FournisseurCloud.GEMINI))
    }

    @Test
    fun `les urls des fournisseurs restent sur leur hote en https`() {
        FournisseurCloud.entries.forEach { f ->
            assertTrue(f.urlGeneration("m").startsWith("https://${f.hote}/"))
            assertTrue(f.urlModeles().startsWith("https://${f.hote}/"))
        }
    }
}
