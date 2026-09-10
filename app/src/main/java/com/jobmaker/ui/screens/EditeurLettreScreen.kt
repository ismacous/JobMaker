package com.jobmaker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.Champ
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.DocumentsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditeurLettreScreen(
    candidatureId: String,
    vm: DocumentsViewModel,
    onRetour: () -> Unit,
) {
    LaunchedEffect(candidatureId) { vm.charger(candidatureId) }
    val candidature by vm.courante.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier la lettre") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    candidature?.let { c ->
                        val mots = c.lettre.paragraphes.sumOf { p ->
                            p.split(Regex("\\s+")).count { it.isNotBlank() }
                        }
                        Text(
                            "$mots mots",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (mots in 200..350) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val c = candidature ?: return@Scaffold
        val lettre = c.lettre

        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Bandeau(
                "Vous modifiez le texte de CETTE lettre, pas votre profil. Chaque paragraphe " +
                    "se reecrit librement, se deplace, se supprime, et vous pouvez en ajouter. " +
                    "Tout est enregistre au fur et a mesure.\n\n" +
                    "Une lettre de 250 a 330 mots est lue jusqu'au bout ; au-dela de 400 elle " +
                    "est survolee. Le compteur en haut a droite passe au vert dans la bonne " +
                    "plage.",
                TypeBandeau.INFO,
            )

            SectionCarte("En-tete") {
                Champ(lettre.objet, { v -> vm.majLettre { it.copy(objet = v) } }, "Objet")
                Champ(lettre.destinataire, { v -> vm.majLettre { it.copy(destinataire = v) } },
                    "Destinataire", lignes = 2,
                    aide = "Service recrutement, nom du contact si vous le connaissez.")
                Champ(lettre.lieuEtDate, { v -> vm.majLettre { it.copy(lieuEtDate = v) } },
                    "Lieu et date")
                Champ(lettre.salutation, { v -> vm.majLettre { it.copy(salutation = v) } },
                    "Formule d'appel")
            }

            SectionCarte(
                "Corps de la lettre",
                sousTitre = "${lettre.paragraphes.size} paragraphe(s)",
            ) {
                lettre.paragraphes.forEachIndexed { index, paragraphe ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                roleParagraphe(index),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(
                                onClick = {
                                    vm.majLettre {
                                        it.copy(paragraphes = deplacerTexte(it.paragraphes, index, -1))
                                    }
                                },
                                enabled = index > 0,
                            ) { Icon(Icons.Default.ArrowUpward, "Monter", Modifier.size(18.dp)) }
                            IconButton(
                                onClick = {
                                    vm.majLettre {
                                        it.copy(paragraphes = deplacerTexte(it.paragraphes, index, 1))
                                    }
                                },
                                enabled = index < lettre.paragraphes.lastIndex,
                            ) { Icon(Icons.Default.ArrowDownward, "Descendre", Modifier.size(18.dp)) }
                            IconButton(onClick = {
                                vm.majLettre {
                                    it.copy(
                                        paragraphes = it.paragraphes.filterIndexed { i, _ -> i != index }
                                    )
                                }
                            }) { Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp)) }
                        }
                        Champ(
                            paragraphe,
                            { v ->
                                vm.majLettre { l ->
                                    l.copy(
                                        paragraphes = l.paragraphes.toMutableList()
                                            .also { it[index] = v }
                                    )
                                }
                            },
                            "Paragraphe ${index + 1}",
                            lignes = 4,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { vm.majLettre { it.copy(paragraphes = it.paragraphes + "") } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Text("  Ajouter un paragraphe")
                }
            }

            SectionCarte("Fin de lettre") {
                Champ(lettre.formulePolitesse, { v ->
                    vm.majLettre { it.copy(formulePolitesse = v) }
                }, "Formule de politesse", lignes = 2)
                Champ(lettre.signature, { v -> vm.majLettre { it.copy(signature = v) } },
                    "Signature")
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

/** Rappelle la structure attendue, pour guider les retouches manuelles. */
private fun roleParagraphe(index: Int): String = when (index) {
    0 -> "1. Vous — le besoin de l'entreprise"
    1 -> "2. Moi — la preuve tiree du parcours"
    2 -> "3. Nous — ce que vous apporterez"
    3 -> "4. Conclusion — disponibilite et rencontre"
    else -> "Paragraphe ${index + 1}"
}

private fun deplacerTexte(liste: List<String>, index: Int, delta: Int): List<String> {
    val cible = (index + delta).coerceIn(0, liste.size - 1)
    if (cible == index) return liste
    val copie = liste.toMutableList()
    copie.add(cible, copie.removeAt(index))
    return copie
}
