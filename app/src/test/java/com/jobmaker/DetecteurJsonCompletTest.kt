package com.jobmaker

import com.jobmaker.llm.DetecteurJsonComplet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce detecteur decide quand couper la generation. Une erreur dans un sens
 * coute des minutes d'ecriture inutile ; dans l'autre, elle tronque un CV.
 * Les cas ci-dessous sont ceux que produit reellement un petit modele.
 */
class DetecteurJsonCompletTest {

    /** Alimente le detecteur token par token, comme le fait le moteur. */
    private fun parMorceaux(texte: String, taille: Int = 3): Pair<Boolean, Int> {
        val d = DetecteurJsonComplet()
        var consommes = 0
        var i = 0
        while (i < texte.length) {
            val bout = texte.substring(i, minOf(i + taille, texte.length))
            consommes += bout.length
            if (d.avaler(bout)) return true to consommes
            i += taille
        }
        return false to consommes
    }

    @Test
    fun `un objet complet est reconnu des sa derniere accolade`() {
        val (coupe, consommes) = parMorceaux("""{"poste": "Magasinier"}  et voila !""")
        assertTrue(coupe)
        assertTrue("la coupure doit tomber sur l'accolade fermante", consommes <= 25)
    }

    @Test
    fun `un objet incomplet ne declenche rien`() {
        val (coupe, _) = parMorceaux("""{"poste": "Magasinier", "missions": ["Reception"""")
        assertFalse(coupe)
    }

    @Test
    fun `les accolades dans le texte des champs ne comptent pas`() {
        val d = DetecteurJsonComplet()
        assertFalse(d.avaler("""{"accroche": "Utilise la syntaxe {clef} au quotidien"""))
        assertTrue(d.avaler("""}"""))
    }

    @Test
    fun `un guillemet echappe ne ferme pas la chaine`() {
        val d = DetecteurJsonComplet()
        // Contenu reel : {"titre": "Poste dit \"cariste\" }"
        assertFalse(d.avaler("{\"titre\": \"Poste dit \\\"cariste\\\" }\""))
        assertTrue(d.avaler("}"))
    }

    @Test
    fun `l'imbrication est suivie jusqu'au dernier niveau`() {
        val json = """{"cv": {"experiences": [{"puces": ["a", "b"]}]}, "lettre": {"objet": "x"}}"""
        val (coupe, consommes) = parMorceaux(json + " Merci de votre lecture.")
        assertTrue(coupe)
        assertTrue(consommes <= json.length + 3)
    }

    @Test
    fun `un brouillon de JSON dans un bloc de raisonnement est ignore`() {
        val texte = """<think>Je vais repondre {"poste": "test"} puis verifier.</think>""" +
            """{"poste": "Magasinier", "entreprise": "Leroy Merlin"}"""
        val (coupe, consommes) = parMorceaux(texte)
        assertTrue(coupe)
        assertTrue(
            "la coupure ne doit pas tomber dans le bloc de raisonnement",
            consommes > texte.indexOf("</think>"),
        )
    }

    @Test
    fun `une balise de raisonnement coupee entre deux tokens est reconnue`() {
        val d = DetecteurJsonComplet()
        // "<thi" / "nk>" : c'est exactement ainsi que le modele l'emet.
        assertFalse(d.avaler("<thi"))
        assertFalse(d.avaler("nk>"))
        assertFalse(d.avaler("""{"essai": 1}"""))
        assertFalse(d.avaler("</thin"))
        assertFalse(d.avaler("k>"))
        assertFalse(d.avaler("""{"poste": "Cariste"""))
        assertTrue(d.avaler("""}"""))
    }

    @Test
    fun `un tableau de premier niveau est reconnu aussi`() {
        val d = DetecteurJsonComplet()
        assertTrue(d.avaler("""["a", "b"]"""))
    }

    @Test
    fun `du texte sans aucun JSON ne declenche jamais`() {
        val (coupe, _) = parMorceaux("Je suis desole, je ne peux pas repondre a cette demande.")
        assertFalse(coupe)
    }
}
