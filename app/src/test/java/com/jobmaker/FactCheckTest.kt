package com.jobmaker

import com.jobmaker.agents.FactCheck
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.CvExperience
import com.jobmaker.data.model.CvFormation
import com.jobmaker.data.model.Experience
import com.jobmaker.data.model.Formation
import com.jobmaker.data.model.Identite
import com.jobmaker.data.model.JobAnalysis
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le garde-fou le plus important de l'application : un CV qui contient un
 * employeur ou un chiffre que le candidat n'a jamais donne coute le poste ET
 * la credibilite en entretien. Ces controles ne dependent pas de l'IA.
 */
class FactCheckTest {

    private val profil = Profile(
        identite = Identite(prenom = "Ismael", nom = "Saini", ville = "Lille"),
        presentation = "Magasinier avec 3 ans d'experience en entrepot.",
        experiences = listOf(
            Experience(
                poste = "Magasinier",
                entreprise = "Leroy Merlin",
                lieu = "Lille",
                dateDebut = "2021",
                dateFin = "2024",
                missions = listOf("Reception des livraisons", "Preparation de commandes"),
                realisations = listOf("Traitement de 120 commandes par jour"),
                outils = listOf("CACES 3", "SAP"),
            )
        ),
        formations = listOf(
            Formation(diplome = "Bac pro logistique", etablissement = "Lycee Baggio")
        ),
    )

    private val offre = JobAnalysis(
        poste = "Preparateur de commandes",
        motsClesAts = listOf("preparation de commandes", "CACES 3", "SAP", "inventaire"),
    )

    private val cvFidele = CvContent(
        titre = "Preparateur de commandes",
        accroche = "Magasinier confirme, 3 ans en entrepot.",
        experiences = listOf(
            CvExperience(
                poste = "Magasinier", entreprise = "Leroy Merlin", lieu = "Lille",
                periode = "2021 - 2024",
                puces = listOf(
                    "Assurer la reception des livraisons et le controle des quantites",
                    "Gerer la preparation de commandes, jusqu'a 120 commandes par jour",
                    "Utiliser SAP et le CACES 3 au quotidien",
                ),
            )
        ),
        formations = listOf(
            CvFormation(diplome = "Bac pro logistique", etablissement = "Lycee Baggio")
        ),
    )

    @Test
    fun `un CV fidele au profil ne declenche aucune alerte`() {
        val rapport = FactCheck.verifier(profil, offre, cvFidele, LetterContent())
        assertFalse(
            "orgs=${rapport.organisationsSuspectes} chiffres=${rapport.chiffresSuspects}",
            rapport.aDesAlertes,
        )
    }

    @Test
    fun `un employeur absent du profil est signale`() {
        val cv = cvFidele.copy(
            experiences = cvFidele.experiences + CvExperience(
                poste = "Chef d'equipe", entreprise = "Amazon Logistique", periode = "2019 - 2021",
            )
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertTrue(
            "Amazon doit etre signale : ${rapport.organisationsSuspectes}",
            rapport.organisationsSuspectes.any { it.contains("Amazon") },
        )
    }

    @Test
    fun `un nom d'employeur reformule reste accepte`() {
        val cv = cvFidele.copy(
            experiences = listOf(cvFidele.experiences[0].copy(entreprise = "Leroy Merlin Lille Sud"))
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertTrue(rapport.organisationsSuspectes.isEmpty())
    }

    @Test
    fun `les chiffres inventes sont signales et les vrais laisses tranquilles`() {
        val cv = cvFidele.copy(
            experiences = listOf(
                cvFidele.experiences[0].copy(
                    puces = listOf(
                        "Reduire les couts de 47 % sur le stock",
                        "Encadrer 15 personnes",
                        "Preparer jusqu'a 120 commandes par jour",
                    )
                )
            )
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertTrue("47 attendu : ${rapport.chiffresSuspects}",
            rapport.chiffresSuspects.any { it.contains("47") })
        assertTrue("15 attendu : ${rapport.chiffresSuspects}",
            rapport.chiffresSuspects.any { it.contains("15") })
        assertFalse("120 vient du profil : ${rapport.chiffresSuspects}",
            rapport.chiffresSuspects.contains("120"))
    }

    @Test
    fun `les annees ne sont pas prises pour des chiffres inventes`() {
        val cv = cvFidele.copy(
            experiences = listOf(cvFidele.experiences[0].copy(periode = "2019 - 2020"))
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertTrue(rapport.chiffresSuspects.none { it.startsWith("20") })
    }

    @Test
    fun `la couverture des mots-cles est mesuree`() {
        val rapport = FactCheck.verifier(profil, offre, cvFidele, LetterContent())
        assertTrue("score trop bas : ${rapport.scoreAts}", rapport.scoreAts >= 70)
        assertTrue(
            "le mot-cle absent doit etre liste : ${rapport.motsClesAbsents}",
            rapport.motsClesAbsents.any { it.contains("inventaire", ignoreCase = true) },
        )
    }

    @Test
    fun `la premiere personne dans le CV est detectee`() {
        val cv = cvFidele.copy(accroche = "Je suis magasinier et je cherche un poste.")
        assertTrue(FactCheck.verifier(profil, offre, cv, LetterContent()).premierePersonneDansCv)
    }

    @Test
    fun `une lettre trop longue est mesuree`() {
        val lettre = LetterContent(paragraphes = List(6) { "mot ".repeat(80) })
        val rapport = FactCheck.verifier(profil, offre, cvFidele, lettre)
        assertTrue("mots comptes : ${rapport.motsLettre}", rapport.motsLettre > 400)
    }

    @Test
    fun `la normalisation ignore accents, casse et ponctuation`() {
        assertEquals("cafe du coin", FactCheck.normaliser("Café  du  Coin !"))
    }

    // -- le diplome exige par l'annonce, recopie dans le CV --------------------
    // Cas reellement survenu : l'annonce exigeait un diplome d'Etat de
    // travailleur social, le candidat ne l'avait pas, et le CV produit
    // l'annoncait quand meme. C'est un faux, verifiable en un appel.

    @Test
    fun `un diplome absent du profil est signale`() {
        val cvMenteur = cvFidele.copy(
            formations = listOf(
                CvFormation(
                    diplome = "Diplome d'Etat d'Assistant de Service Social",
                    etablissement = "Lycee Baggio",
                )
            )
        )
        val rapport = FactCheck.verifier(profil, offre, cvMenteur, LetterContent())
        assertTrue(
            "Le diplome invente doit etre signale",
            rapport.diplomesSuspects.any { it.contains("Assistant de Service Social") },
        )
        assertTrue(rapport.aDesAlertes)
    }

    @Test
    fun `le diplome reellement declare ne declenche rien`() {
        val rapport = FactCheck.verifier(profil, offre, cvFidele, LetterContent())
        assertEquals(emptyList<String>(), rapport.diplomesSuspects)
    }

    @Test
    fun `une reformulation du meme diplome reste acceptee`() {
        val cv = cvFidele.copy(
            formations = listOf(CvFormation(diplome = "Bac professionnel logistique"))
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertEquals(
            "Un intitule reformule ne doit pas passer pour un faux",
            emptyList<String>(), rapport.diplomesSuspects,
        )
    }

    @Test
    fun `un diplome de niveau superieur au profil est signale`() {
        val cv = cvFidele.copy(
            formations = listOf(CvFormation(diplome = "Licence professionnelle logistique"))
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertTrue(
            "Une licence n'est pas une reformulation d'un bac pro, meme domaine",
            rapport.diplomesSuspects.any { it.contains("Licence") },
        )
    }
}
