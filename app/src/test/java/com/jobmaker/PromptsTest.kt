package com.jobmaker

import com.jobmaker.agents.Prompts
import com.jobmaker.data.model.JobAnalysis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le profil du candidat pese a lui seul environ 2 500 tokens, et une
 * candidature fait trois appels. Tant qu'il n'apparait qu'en tete, identique a
 * chaque fois, le fournisseur le met en cache et ne le facture qu'une seule
 * fois -- les tokens caches ne comptent pas dans le quota par minute.
 *
 * Le jour ou quelqu'un remettra le profil dans un message d'etape "pour etre
 * sur qu'il le voie", le cache sera manque a chaque appel et le quota gratuit
 * repartira en fumee sans que rien ne casse visiblement. D'ou ces tests.
 */
class PromptsTest {

    private val profil = """
        IDENTITE
          Nom : Jean Temoin
        EXPERIENCES PROFESSIONNELLES
          [E1] Magasinier cariste
               Entreprise : Etablissement Temoin
    """.trimIndent()

    @Test
    fun `le prefixe commun porte le profil et les regles`() {
        val prefixe = Prompts.prefixeCommun(profil)
        assertTrue(prefixe.contains("Jean Temoin"))
        assertTrue(prefixe.contains("Etablissement Temoin"))
        // Les deux regles qui ne doivent jamais manquer a un appel.
        assertTrue(prefixe.contains("REGLE DE VERITE"))
        assertTrue(prefixe.contains("REGLES DE FORMAT"))
    }

    @Test
    fun `aucune etape ne renvoie le profil apres le prefixe`() {
        val etapes = listOf(
            Prompts.preparationUser("Annonce : magasinier cariste, CACES 3."),
            Prompts.redactionUser(
                analyse = JobAnalysis(poste = "Magasinier"),
                strategie = com.jobmaker.data.model.Strategy(),
                nomComplet = "Jean Temoin",
                langue = "fr",
                disponibilite = "immediate",
                unePage = true,
            ),
            Prompts.revisionUser(
                analyse = JobAnalysis(poste = "Magasinier"),
                cvJson = "{}",
                lettreJson = "{}",
                langue = "fr",
            ),
        )
        etapes.forEach { etape ->
            assertFalse(
                "Une etape renvoie le profil : le cache du fournisseur sera manque",
                etape.contains("Etablissement Temoin"),
            )
        }
    }

    @Test
    fun `le prefixe ne contient rien de propre a une etape`() {
        // S'il variait d'une etape a l'autre, il ne serait plus mis en cache.
        val prefixe = Prompts.prefixeCommun(profil)
        listOf("ANNONCE", "CV ACTUEL", "LETTRE ACTUELLE", "STRATEGIE RETENUE")
            .forEach { marqueur ->
                assertFalse(
                    "Le prefixe porte \"$marqueur\", qui change a chaque etape",
                    prefixe.contains("--- $marqueur"),
                )
            }
    }
}
