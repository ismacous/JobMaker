package com.jobmaker

import com.jobmaker.agents.Typographie
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le tiret cadratin est ce qui fait dire a un recruteur "c'est ecrit par une
 * IA" avant meme d'avoir lu la phrase. Ces cas sont ceux que produisent
 * reellement les modeles en francais.
 */
class TypographieTest {

    @Test
    fun `une incise entre tirets cadratins devient une incise entre virgules`() {
        assertEquals(
            "Un profil rigoureux, habitue a la cadence, et disponible.",
            Typographie.assainir("Un profil rigoureux \u2014 habitue a la cadence \u2014 et disponible."),
        )
    }

    @Test
    fun `le demi-cadratin est traite comme le cadratin`() {
        assertEquals(
            "Reception, controle, rangement",
            Typographie.assainir("Reception \u2013 controle \u2013 rangement"),
        )
    }

    @Test
    fun `le trait d'union isole entre espaces est une incise`() {
        assertEquals(
            "Gestion des stocks, environ 200 references",
            Typographie.assainir("Gestion des stocks - environ 200 references"),
        )
    }

    @Test
    fun `les mots composes ne sont jamais touches`() {
        val texte = "Sous-traitance a Aix-en-Provence, poste d'agent technico-commercial."
        assertEquals(texte, Typographie.assainir(texte))
    }

    @Test
    fun `une incise en fin de phrase ne laisse pas de virgule orpheline`() {
        assertEquals(
            "Disponible immediatement.",
            Typographie.assainir("Disponible immediatement \u2014."),
        )
    }

    @Test
    fun `une virgule deja presente ne se dedouble pas`() {
        assertEquals(
            "Rigueur, methode et cadence.",
            Typographie.assainir("Rigueur, \u2014 methode et cadence."),
        )
    }

    @Test
    fun `un texte vide reste vide`() {
        assertEquals("", Typographie.assainir(""))
    }

    @Test
    fun `la variante liste retire les entrees devenues vides`() {
        assertEquals(
            listOf("Reception, controle"),
            Typographie.assainir(listOf("Reception \u2014 controle", "   ", "\u2014")),
        )
    }
}
