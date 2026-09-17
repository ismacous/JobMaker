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

    // -----------------------------------------------------------------------
    // Les trois marques de machine
    //
    // Reprises d'une vraie candidature produite par l'application : un CV dont
    // l'accroche disait ce que le candidat cherchait, et une lettre qui ouvrait
    // en resumant l'annonce puis annoncait un plan pour les premiers mois. Les
    // trois venaient des consignes elles-memes, qui les demandaient.
    // -----------------------------------------------------------------------

    @Test
    fun `une accroche qui dit ce que le candidat cherche est signalee`() {
        val cv = cvFidele.copy(
            accroche = "Professionnel de la logistique, rigoureux et organise. " +
                "Recherche un CDD junior ou les competences relationnelles sont centrales.",
        )
        assertTrue(FactCheck.verifier(profil, offre, cv, LetterContent())
            .accrocheParleDeRecherche)
    }

    @Test
    fun `une accroche tournee vers l'apport passe sans alerte`() {
        val cv = cvFidele.copy(
            accroche = "Magasinier habitue a la preparation de commandes et a la " +
                "coordination d'equipe. Ecoute et mediation acquises en entrepot.",
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertFalse(rapport.accrocheParleDeRecherche)
    }

    @Test
    fun `le mot recherche dans son sens normal ne declenche rien`() {
        // "recherche de solutions" est une competence, pas une demande d'emploi.
        val cv = cvFidele.copy(
            accroche = "Magasinier oriente recherche de solutions et amelioration continue.",
        )
        assertFalse(FactCheck.verifier(profil, offre, cv, LetterContent())
            .accrocheParleDeRecherche)
    }

    @Test
    fun `un premier paragraphe qui decrit le poste est signale`() {
        val lettre = LetterContent(
            paragraphes = listOf(
                "Le dispositif de mediation en sante mentale requiert une capacite a " +
                    "instaurer un climat de confiance et a faciliter le dialogue.",
                "Chez CapTrain, j'ai assure la reception et le tri de trains de fret.",
            )
        )
        assertTrue(FactCheck.verifier(profil, offre, cvFidele, lettre).lettreResumeLAnnonce)
    }

    @Test
    fun `un premier paragraphe qui parle du candidat passe`() {
        val lettre = LetterContent(
            paragraphes = listOf(
                "Votre maraude aupres des personnes sans abri rejoint ce que je fais " +
                    "depuis deux ans en benevolat, et c'est pour cela que je postule.",
            )
        )
        assertFalse(FactCheck.verifier(profil, offre, cvFidele, lettre).lettreResumeLAnnonce)
    }

    @Test
    fun `un plan pour les premiers mois est signale`() {
        val lettre = LetterContent(
            paragraphes = listOf(
                "Votre approche de terrain me parle.",
                "Dans les premiers mois, je pourrai mettre a profit mon experience de " +
                    "coordination pour accompagner les usagers.",
            )
        )
        assertTrue(FactCheck.verifier(profil, offre, cvFidele, lettre)
            .lettreProjetteLesPremiersMois)
    }

    @Test
    fun `tout le futur sur le travail est signale, pas seulement les premiers mois`() {
        // Le modele qui contourne une formule interdite en trouve une autre :
        // c'est la famille entiere qu'il faut viser.
        val tournures = listOf(
            "Je compte structurer le reporting quotidien.",
            "Je pourrai apporter ma rigueur sur le terrain.",
            "J'assurerai le deploiement des supports en magasin.",
            "Des mon arrivee, je prendrai en charge les points de vente.",
            "Cette approche me permettra de garantir la visibilite des produits.",
            "Je serai en mesure de tenir les delais de mise en rayon.",
        )
        tournures.forEach { phrase ->
            val lettre = LetterContent(
                paragraphes = listOf("Votre reseau de magasins m'interesse.", phrase,
                    "Je reste disponible pour en echanger."),
            )
            assertTrue(
                "non detecte : \"$phrase\"",
                FactCheck.verifier(profil, offre, cvFidele, lettre)
                    .lettreProjetteLesPremiersMois,
            )
        }
    }

    @Test
    fun `un plan numerote est signale meme sans formule interdite`() {
        val lettre = LetterContent(
            paragraphes = listOf(
                "Votre reseau de magasins m'interesse.",
                "Mon approche du terrain : 1) auditer les points de vente, " +
                    "2) deployer les supports, 3) transmettre le reporting.",
                "Je reste disponible pour en echanger.",
            )
        )
        assertTrue(FactCheck.verifier(profil, offre, cvFidele, lettre)
            .lettreProjetteLesPremiersMois)
    }

    @Test
    fun `la conclusion garde le droit au futur`() {
        // "je pourrai vous rencontrer" en derniere phrase est normal : c'est le
        // corps de la lettre qui ne doit rien promettre.
        val lettre = LetterContent(
            paragraphes = listOf(
                "Votre reseau de magasins m'interesse.",
                "Chez CapTrain, j'ai coordonne la preparation des equipements.",
                "Disponible immediatement, je pourrai vous rencontrer quand vous voudrez.",
            )
        )
        assertFalse(FactCheck.verifier(profil, offre, cvFidele, lettre)
            .lettreProjetteLesPremiersMois)
    }

    @Test
    fun `une lettre sans plan d'integration passe`() {
        val lettre = LetterContent(
            paragraphes = listOf(
                "Votre approche de terrain me parle.",
                "Chez CapTrain, j'ai coordonne les envois avec le poste de controle.",
                "Je reste disponible pour en echanger.",
            )
        )
        assertFalse(FactCheck.verifier(profil, offre, cvFidele, lettre)
            .lettreProjetteLesPremiersMois)
    }

    @Test
    fun `un chiffre isole en fin de ligne n'est pas signale`() {
        // Vu sur telephone : le bandeau annonçait "chiffres non presents dans le
        // profil (1" suivi d'un retour a la ligne. Le motif d'extraction accepte
        // les blancs, et seul l'espace etait retire : un "1" en fin de puce
        // ressortait "1\n", donc deux caracteres, donc echappait a la regle qui
        // ignore les nombres d'un seul chiffre.
        val cv = cvFidele.copy(
            experiences = listOf(
                cvFidele.experiences[0].copy(
                    puces = listOf(
                        "Travailler en equipe de 3",
                        "Gerer le magasin niveau 1",
                    )
                )
            )
        )
        val rapport = FactCheck.verifier(profil, offre, cv, LetterContent())
        assertTrue(
            "aucun chiffre a un seul caractere ne doit sortir : " +
                "${rapport.chiffresSuspects}",
            rapport.chiffresSuspects.none { it.trim().length <= 1 },
        )
        assertTrue(
            "aucun blanc ne doit subsister : ${rapport.chiffresSuspects}",
            rapport.chiffresSuspects.none { c -> c.any { it.isWhitespace() } },
        )
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
