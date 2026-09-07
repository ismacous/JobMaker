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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.StatutCandidature
import com.jobmaker.ui.components.EtatVide
import com.jobmaker.ui.vm.DocumentsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    vm: DocumentsViewModel,
    onOuvrir: (String) -> Unit,
    onNouvelle: () -> Unit,
) {
    val candidatures by vm.candidatures.collectAsState()
    var aSupprimer by remember { mutableStateOf<Candidature?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mes candidatures") }) },
        floatingActionButton = {
            if (candidatures.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNouvelle,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nouvelle") },
                )
            }
        },
    ) { padding ->
        if (candidatures.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                EtatVide(
                    "Aucune candidature",
                    "Collez une offre d'emploi dans l'onglet Candidater : l'application " +
                        "produira un CV et une lettre adaptes a cette offre precise.",
                ) {
                    Button(onClick = onNouvelle) { Text("Commencer") }
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 8.dp, bottom = 90.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(candidatures, key = { it.id }) { candidature ->
                    CarteCandidature(
                        candidature = candidature,
                        onClick = { onOuvrir(candidature.id) },
                        onSupprimer = { aSupprimer = candidature },
                        onDupliquer = { vm.dupliquer(candidature.id, onOuvrir) },
                        onStatut = { statut -> vm.majStatutDe(candidature.id, statut) },
                    )
                }
            }
        }
    }

    aSupprimer?.let { candidature ->
        AlertDialog(
            onDismissRequest = { aSupprimer = null },
            title = { Text("Supprimer cette candidature ?") },
            text = {
                Text(
                    "Le CV et la lettre pour \"${candidature.analyse.poste}\" seront perdus. " +
                        "Les fichiers PDF deja exportes ne sont pas affectes."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.supprimer(candidature.id)
                    aSupprimer = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { aSupprimer = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun CarteCandidature(
    candidature: Candidature,
    onClick: () -> Unit,
    onSupprimer: () -> Unit,
    onDupliquer: () -> Unit,
    onStatut: (StatutCandidature) -> Unit,
) {
    var menuStatut by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                candidature.analyse.poste.ifBlank { "Poste sans titre" },
                style = MaterialTheme.typography.titleMedium,
            )
            val sousTitre = listOfNotNull(
                candidature.analyse.entreprise.takeIf { it.isNotBlank() },
                candidature.analyse.lieu.takeIf { it.isNotBlank() },
                candidature.analyse.contrat.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (sousTitre.isNotBlank()) {
                Text(
                    sousTitre,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { menuStatut = true },
                    label = { Text(candidature.statut.label) },
                )
                Text(
                    "Mots-cles couverts : ${candidature.scoreAts} %",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidature.scoreAts >= 70) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (candidature.revue.faitsInventes.isNotEmpty()) {
                Text(
                    "⚠ ${candidature.revue.faitsInventes.size} element(s) a verifier",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dateCourte(candidature.modifieLe),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDupliquer) { Text("Dupliquer") }
                TextButton(onClick = onSupprimer) { Text("Supprimer") }
            }
        }
    }

    if (menuStatut) {
        AlertDialog(
            onDismissRequest = { menuStatut = false },
            title = { Text("Ou en est cette candidature ?") },
            text = {
                Column {
                    StatutCandidature.entries.forEach { statut ->
                        TextButton(
                            onClick = { onStatut(statut); menuStatut = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                statut.label,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (statut == candidature.statut) FontWeight.Bold
                                else FontWeight.Normal,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuStatut = false }) { Text("Fermer") }
            },
        )
    }
}

private fun dateCourte(millis: Long): String =
    SimpleDateFormat("d MMM yyyy 'a' HH:mm", Locale.FRENCH).format(Date(millis))
