package com.jobmaker

import com.jobmaker.agents.JsonRepair
import com.jobmaker.data.model.JobAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ces cas ne sont pas theoriques : ce sont les formes que prennent reellement
 * les reponses d'un modele de quelques milliards de parametres qui tourne sur
 * un telephone. Si l'un d'eux casse, une generation entiere de plusieurs
 * minutes est perdue.
 */
class JsonRepairTest {

    private fun analyse(brut: String): JobAnalysis? =
        JsonRepair.clean(brut)?.let {
            runCatching { JsonRepair.lenient.decodeFromString<JobAnalysis>(it) }.getOrNull()
        }

    @Test
    fun `bloc de raisonnement, prose et balises de code sont retires`() {
        val brut = """
            <think>L'utilisateur veut du JSON, je dois suivre le schema.</think>
            Voici l'analyse demandee :
            ```json
            { "poste": "Magasinier", "entreprise": "Leroy Merlin",
              "missions": ["Reception", "Rangement",] }
            ```
            J'espere que cela convient.
        """.trimIndent()

        val resultat = analyse(brut)
        assertNotNull(resultat)
        assertEquals("Magasinier", resultat!!.poste)
        assertEquals("Leroy Merlin", resultat.entreprise)
        assertEquals(2, resultat.missions.size)
    }

    @Test
    fun `un JSON coupe par la limite de tokens est referme`() {
        val brut = """{"poste":"Aide-soignant","missions":["Toilette des patients",""" +
            """"Distribution des repas","Surveill"""

        val resultat = analyse(brut)
        assertNotNull("le JSON tronque doit rester exploitable", resultat)
        assertEquals("Aide-soignant", resultat!!.poste)
        // L'element incomplet est jete, les precedents sont conserves.
        assertEquals(2, resultat.missions.size)
    }

    @Test
    fun `les guillemets typographiques sont normalises`() {
        val resultat = analyse("Reponse : {“poste”: “Vendeur”, “lieu”: “Lyon”}")
        assertEquals("Vendeur", resultat?.poste)
        assertEquals("Lyon", resultat?.lieu)
    }

    @Test
    fun `un objet imbrique complet est isole du bruit`() {
        val brut = "Bien sur !\n{\"adequation\":{\"score\":70,\"atouts\":[\"a\"]}}\nVoila."
        val bloc = JsonRepair.clean(brut)
        assertNotNull(bloc)
        assertTrue(bloc!!.startsWith("{"))
        assertTrue(bloc.endsWith("}"))
    }

    @Test
    fun `un bloc de raisonnement non ferme ne laisse pas passer de faux JSON`() {
        val bloc = JsonRepair.clean("<think>je reflechis sans jamais conclure")
        assertTrue("aucun JSON ne doit etre invente", bloc == null || bloc.isBlank())
    }

    @Test
    fun `le nettoyage de prose retire les balises sans toucher au texte`() {
        val texte = JsonRepair.cleanProse("<think>bla</think>```\nUn magasinier rigoureux.\n```")
        assertEquals("Un magasinier rigoureux.", texte)
    }

    @Test
    fun `un texte sans aucun JSON renvoie null`() {
        assertEquals(null, JsonRepair.clean("Je ne peux pas repondre a cette demande."))
    }
}
