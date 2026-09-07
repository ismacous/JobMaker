package com.jobmaker.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobmaker.data.model.JobExplanation
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.JaugeScore
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.TexteDefilant
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.ExplainViewModel

/**
 * "Comprendre un poste".
 *
 * Repond a un probleme concret des sites d'annonces : la moitie des offres ne
 * dit pas ce qu'on y fait vraiment. On colle l'annonce -- ou juste son
 * intitule -- et on obtient le metier explique en francais simple, le quotidien
 * reel, l'ordre de grandeur du salaire, les questions a poser en entretien et
 * les signaux d'alerte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComprendreScreen(
    vm: ExplainViewModel,
    onOuvrirModeles: () -> Unit,
) {
    val texte by vm.texte.collectAsState()
    val etat by vm.etat.collectAsState()
    val historique by vm.historique.collectAsState()
    val presse = LocalClipboardManager.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Comprendre un poste") }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            if (etat.resultat == null) {
                Text(
                    "Collez une annonce, ou tapez simplement un intitule de poste que vous " +
                        "ne comprenez pas.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
                OutlinedTextField(
                    value = texte,
                    onValueChange = vm::majTexte,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 14,
                    placeholder = { Text("Exemple : \"Chargé de clientèle back office H/F\"") },
                    shape = MaterialTheme.shapes.small,
                    enabled = !etat.enCours,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            presse.getText()?.text?.takeIf { it.isNotBlank() }?.let(vm::majTexte)
                        },
                        enabled = !etat.enCours,
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Text("  Coller")
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (etat.enCours) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(etat.statut, style = MaterialTheme.typography.titleSmall)
                            if (etat.modele.isNotBlank()) {
                                Text(
                                    "Modele : ${etat.modele}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (etat.apercuBrut.isNotBlank()) {
                        TexteDefilant(
                            etat.apercuBrut,
                            Modifier.height(110.dp).padding(top = 8.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = vm::annuler,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Interrompre") }
                } else {
                    Button(
                        onClick = vm::lancer,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = texte.isNotBlank(),
                    ) {
                        Icon(Icons.Default.HelpOutline, null, Modifier.size(20.dp))
                        Text("  Expliquer ce poste")
                    }
                }

                etat.erreur?.let { erreur ->
                    Bandeau(erreur, TypeBandeau.ERREUR) {
                        OutlinedButton(onClick = onOuvrirModeles) { Text("Gerer les modeles") }
                    }
                }

                if (historique.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Deja consulte", style = MaterialTheme.typography.titleMedium)
                    historique.forEach { entree ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable { vm.ouvrirDepuisHistorique(entree) },
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entree.explication.intituleClair.ifBlank {
                                            entree.offreTexte.take(60)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        entree.explication.resumeSimple.take(90),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                TextButton(onClick = { vm.supprimerHistorique(entree.id) }) {
                                    Text("Suppr.")
                                }
                            }
                        }
                    }
                }
            } else {
                Resultat(etat.resultat!!, onRetour = vm::effacer)
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Resultat(explication: JobExplanation, onRetour: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onRetour, modifier = Modifier.padding(top = 4.dp)) {
            Text("← Expliquer un autre poste")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    explication.intituleClair.ifBlank { "Ce poste" },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (explication.resumeSimple.isNotBlank()) {
                    Text(
                        explication.resumeSimple,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (explication.salaireIndicatif.isNotBlank()) {
                    Text(
                        "Salaire indicatif : ${explication.salaireIndicatif}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (explication.cestQuoiConcretement.isNotBlank()) {
            SectionCarte("Concretement") {
                Text(explication.cestQuoiConcretement, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (explication.journeeType.isNotEmpty()) {
            SectionCarte("Une journee type") { Puces(explication.journeeType) }
        }

        if (explication.conditionsTravail.isNotBlank()) {
            SectionCarte("Conditions de travail") {
                Text(explication.conditionsTravail, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (explication.competencesCles.isNotEmpty() || explication.outils.isNotEmpty()) {
            SectionCarte("Ce qu'il faut savoir faire") {
                if (explication.competencesCles.isNotEmpty()) Puces(explication.competencesCles)
                if (explication.outils.isNotEmpty()) {
                    Text(
                        "Outils : ${explication.outils.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        if (explication.formationTypique.isNotBlank()) {
            SectionCarte("Formation habituelle") {
                Text(explication.formationTypique, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // --- adequation avec le profil ---
        if (explication.adequation.score > 0 ||
            explication.adequation.atouts.isNotEmpty()
        ) {
            SectionCarte("Est-ce pour vous ?") {
                JaugeScore(explication.adequation.score, "Adequation avec votre profil")
                Spacer(Modifier.height(8.dp))
                if (explication.adequation.atouts.isNotEmpty()) {
                    Text("Vos atouts", style = MaterialTheme.typography.titleSmall)
                    Puces(explication.adequation.atouts)
                }
                if (explication.adequation.manques.isNotEmpty()) {
                    Text(
                        "Ce qui manque",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Puces(explication.adequation.manques)
                }
                if (explication.adequation.conseils.isNotEmpty()) {
                    Text(
                        "Que faire",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Puces(explication.adequation.conseils)
                }
            }
        }

        if (explication.pointsDeVigilance.isNotEmpty()) {
            SectionCarte("Points de vigilance") {
                Bandeau(
                    explication.pointsDeVigilance.joinToString("\n") { "• $it" },
                    TypeBandeau.ALERTE,
                )
            }
        }

        if (explication.signauxPositifs.isNotEmpty()) {
            SectionCarte("Bons signes", replierParDefaut = true) {
                Puces(explication.signauxPositifs)
            }
        }

        if (explication.questionsAPoser.isNotEmpty()) {
            SectionCarte(
                "Questions a poser en entretien",
                sousTitre = "Poser de bonnes questions fait aussi partie de l'evaluation",
            ) { Puces(explication.questionsAPoser) }
        }

        if (explication.vocabulaire.isNotEmpty()) {
            SectionCarte("Le vocabulaire de l'annonce", replierParDefaut = true) {
                explication.vocabulaire.forEach { terme ->
                    Column(Modifier.padding(bottom = 5.dp)) {
                        Text(
                            terme.terme,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(terme.explication, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (explication.evolutions.isNotEmpty()) {
            SectionCarte("Ou ca peut mener", replierParDefaut = true) {
                Puces(explication.evolutions)
            }
        }
    }
}

@Composable
private fun Puces(items: List<String>) {
    Column {
        items.filter { it.isNotBlank() }.forEach {
            Text(
                "•  $it",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 1.5.dp),
            )
        }
    }
}
