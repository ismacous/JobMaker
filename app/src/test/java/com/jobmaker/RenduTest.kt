package com.jobmaker

import com.jobmaker.agents.ProfileSerializer
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.CvExperience
import com.jobmaker.data.model.Experience
import com.jobmaker.data.model.Identite
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Profile
import com.jobmaker.render.CvTemplates
import com.jobmaker.render.HtmlRenderer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenduTest {

    private val profil = Profile(
        identite = Identite(
            prenom = "Ismael", nom = "Saini", telephone = "06 00 00 00 00",
            email = "moi@exemple.fr", ville = "Lille", codePostal = "59000",
        ),
        experiences = listOf(
            Experience(poste = "Magasinier", entreprise = "Leroy Merlin",
                missions = listOf("Reception"), realisations = listOf("120 commandes par jour"))
        ),
    )

    private val cv = CvContent(
        titre = "Preparateur de commandes",
        accroche = "Magasinier confirme.",
        experiences = listOf(
            CvExperience(poste = "Magasinier", entreprise = "Leroy Merlin",
                periode = "2021 - 2024", puces = listOf("Assurer la reception"))
        ),
    )

    @Test
    fun `chaque gabarit produit un document A4 complet`() {
        CvTemplates.tous.forEach { gabarit ->
            val html = HtmlRenderer.renderCv(profil, cv, gabarit, "#1F4E79")
            assertTrue("${gabarit.id} : doctype", html.startsWith("<!DOCTYPE html>"))
            assertTrue("${gabarit.id} : fermeture", html.trimEnd().endsWith("</html>"))
            assertTrue("${gabarit.id} : format A4", html.contains("size: A4"))
            assertTrue("${gabarit.id} : couleur d'accent", html.contains("--accent: #1F4E79"))
        }
    }

    @Test
    fun `l'identite vient du profil et jamais du modele`() {
        // Le modele n'a aucun champ ou glisser un numero de telephone : l'application
        // les insere elle-meme au rendu.
        CvTemplates.tous.forEach { gabarit ->
            val html = HtmlRenderer.renderCv(profil, cv, gabarit, "#000000")
            assertTrue("${gabarit.id} : nom", html.contains("Ismael Saini"))
            assertTrue("${gabarit.id} : telephone", html.contains("06 00 00 00 00"))
            assertTrue("${gabarit.id} : email", html.contains("moi@exemple.fr"))
        }
    }

    @Test
    fun `le contenu genere est echappe`() {
        val cvPiege = cv.copy(titre = "Dev <script>alert(1)</script> & \"co\"")
        val html = HtmlRenderer.renderCv(profil, cvPiege, CvTemplates.sobre, "#000000")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp;"))
    }

    @Test
    fun `la lettre est rendue avec ses paragraphes`() {
        val lettre = LetterContent(
            objet = "Candidature au poste de preparateur",
            paragraphes = listOf("Premier paragraphe.", "Second paragraphe."),
            signature = "Ismael Saini",
        )
        val html = HtmlRenderer.renderLettre(profil, lettre, "#1F4E79")
        assertTrue(html.contains("Candidature au poste de preparateur"))
        assertTrue(html.contains("Premier paragraphe."))
        assertTrue(html.contains("Second paragraphe."))
        assertTrue(html.contains("Ismael Saini"))
    }

    @Test
    fun `les experiences recoivent une etiquette courte reversible`() {
        val digest = ProfileSerializer.digest(profil)
        assertEquals(profil.experiences[0].id, digest.idReel("E1"))
        assertTrue(digest.texte.contains("Leroy Merlin"))
        assertTrue(digest.texte.contains("120 commandes par jour"))
    }

    @Test
    fun `le profil et la candidature survivent a un aller-retour JSON`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        assertEquals(
            profil,
            json.decodeFromString(Profile.serializer(), json.encodeToString(Profile.serializer(), profil)),
        )
        val candidature = Candidature(cv = cv, lettre = LetterContent(objet = "x"))
        assertEquals(
            candidature,
            json.decodeFromString(
                Candidature.serializer(),
                json.encodeToString(Candidature.serializer(), candidature),
            ),
        )
    }

    @Test
    fun `la completude du profil reflete ce qui manque`() {
        assertEquals(0, Profile().completude)
        assertTrue(Profile().manques.isNotEmpty())
        assertTrue(profil.completude > 40)
    }

    @Test
    fun `un seul gabarit est signale comme risque pour les filtres automatiques`() {
        val nonSurs = CvTemplates.tous.filterNot { it.atsSafe }.map { it.id }
        assertEquals(listOf("moderne"), nonSurs)
    }
}
