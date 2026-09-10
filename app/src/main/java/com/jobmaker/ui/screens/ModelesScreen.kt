package com.jobmaker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobmaker.llm.CatalogModel
import com.jobmaker.llm.DownloadState
import com.jobmaker.llm.ModelTier
import com.jobmaker.llm.formatBytes
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.ModelsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelesScreen(vm: ModelsViewModel, onRetour: () -> Unit) {
    val installes by vm.installes.collectAsState()
    val telechargements by vm.telechargements.collectAsState()
    val message by vm.message.collectAsState()
    val resultatTest by vm.resultatTest.collectAsState()
    val testEnCours by vm.testEnCours.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var aSupprimer by remember { mutableStateOf<String?>(null) }
    var infoMoteur by remember { mutableStateOf("") }
    var lienModele by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { infoMoteur = vm.infoMoteur() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consommerMessage() }
    }

    val importGguf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { vm.importer(it, it.lastPathSegment ?: "modele-importe.gguf") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modeles d'IA") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            if (!vm.moteurNatifDisponible) {
                Bandeau(
                    "Le moteur d'inference natif n'est pas present dans cette version de " +
                        "l'application. Elle a ete compilee sans la partie C++ " +
                        "(jobmaker.skipNative=true) : la generation ne fonctionnera pas.",
                    TypeBandeau.ERREUR,
                )
            }

            Bandeau(
                "Les modeles se telechargent une seule fois et fonctionnent ensuite " +
                    "entierement hors ligne. Espace disponible : " +
                    formatBytes(vm.espaceLibre) + ".\n\n" +
                    "Gardez l'application ouverte pendant le telechargement. S'il " +
                    "s'interrompt, il reprend la ou il s'etait arrete.",
                TypeBandeau.INFO,
            )

            if (installes.isEmpty()) {
                Text(
                    "Commencez par \"Qwen3 4B Instruct (Q4)\" : c'est le meilleur compromis " +
                        "entre qualite et vitesse pour rediger des CV, et il suffit a lui seul " +
                        "pour tout faire fonctionner.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // --- modeles installes ---
            if (installes.isNotEmpty()) {
                Text(
                    "Installes sur l'appareil",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                installes.forEach { modele ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(modele.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                modele.sizeLabel + if (!modele.fromCatalog) " · importe" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { vm.tester(modele.id) },
                                    enabled = !testEnCours,
                                ) {
                                    if (testEnCours) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    }
                                    Text(if (testEnCours) "  Test..." else "Tester")
                                }
                                OutlinedButton(
                                    onClick = { vm.diagnostiquer(modele.id) },
                                    enabled = !testEnCours,
                                ) {
                                    Text("Mesurer")
                                }
                            }
                            Row(
                                Modifier.padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(onClick = { vm.definirPourTousLesRoles(modele.id) }) {
                                    Text("Tout utiliser")
                                }
                                TextButton(onClick = { aSupprimer = modele.id }) {
                                    Text("Supprimer")
                                }
                            }
                        }
                    }
                }
            }

            // --- catalogue ---
            Text(
                "Catalogue",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            vm.catalogue.forEach { modele ->
                CarteModele(
                    modele = modele,
                    installe = installes.any { it.id == modele.id },
                    etat = telechargements[modele.id],
                    onTelecharger = { vm.telecharger(modele) },
                    onAnnuler = { vm.annuler(modele.id) },
                    onSupprimer = { aSupprimer = modele.id },
                )
            }

            // --- telechargement par lien ---
            Text(
                "Telecharger depuis un lien",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
            )
            Text(
                "Si un modele du catalogue refuse de se telecharger (erreur 404), c'est que " +
                    "son depot a change de nom. Cherchez-le sur huggingface.co depuis le " +
                    "navigateur du telephone, appuyez longuement sur le bouton de " +
                    "telechargement du fichier .gguf, copiez le lien, et collez-le ici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = lienModele,
                onValueChange = { lienModele = it },
                label = { Text("https://huggingface.co/.../fichier.gguf") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
            val etatLien = telechargements[vm.idDepuisLien(lienModele)]
            when {
                lienModele.isBlank() -> Unit

                etatLien is DownloadState.Running -> Column(Modifier.padding(top = 6.dp)) {
                    LinearProgressIndicator(
                        progress = { etatLien.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (etatLien.bytesTotal > 0)
                            "${formatBytes(etatLien.bytesDone)} / " +
                                "${formatBytes(etatLien.bytesTotal)} · " +
                                "${formatBytes(etatLien.bytesPerSecond)}/s"
                        else "${formatBytes(etatLien.bytesDone)} recus",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = { vm.annuler(vm.idDepuisLien(lienModele)) }) {
                        Text("Annuler")
                    }
                }

                etatLien is DownloadState.Resolving -> Text(
                    etatLien.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )

                etatLien is DownloadState.Failed -> Column(Modifier.padding(top = 6.dp)) {
                    Text(
                        etatLien.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { vm.telechargerDepuisLien(lienModele) }) {
                        Text("Reessayer")
                    }
                }

                etatLien is DownloadState.Done -> Text(
                    "Modele installe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 6.dp),
                )

                else -> Button(
                    onClick = { vm.telechargerDepuisLien(lienModele) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("  Telecharger ce fichier")
                }
            }

            // --- import manuel ---
            Text(
                "Import manuel",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
            )
            Text(
                "Si vous avez deja un fichier .gguf sur le telephone (telecharge avec le " +
                    "navigateur, recu par transfert), importez-le ici. N'importe quel modele " +
                    "au format GGUF fonctionne.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { importGguf.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("Importer un fichier .gguf") }

            if (infoMoteur.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Moteur",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    infoMoteur,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    resultatTest?.let { resultat ->
        val presse = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = vm::effacerTest,
            title = { Text("Test du modele") },
            text = {
                // Le diagnostic fait une vingtaine de lignes : sans defilement,
                // la fin est coupee et c'est justement la qu'est la conclusion.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(resultat, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::effacerTest) { Text("Fermer") } },
            dismissButton = {
                TextButton(onClick = { presse.setText(AnnotatedString(resultat)) }) {
                    Text("Copier")
                }
            },
        )
    }

    aSupprimer?.let { id ->
        AlertDialog(
            onDismissRequest = { aSupprimer = null },
            title = { Text("Supprimer ce modele ?") },
            text = { Text("Le fichier sera efface. Vous pourrez le retelecharger plus tard.") },
            confirmButton = {
                TextButton(onClick = { vm.supprimer(id); aSupprimer = null }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { aSupprimer = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun CarteModele(
    modele: CatalogModel,
    installe: Boolean,
    etat: DownloadState?,
    onTelecharger: () -> Unit,
    onAnnuler: () -> Unit,
    onSupprimer: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (installe) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modele.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(ModelsViewModel.libelleTier(modele.tier)) },
                )
            }
            Text(
                "${modele.approxSizeLabel} · ${modele.minRamGb} Go de RAM conseilles · " +
                    modele.languages,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (modele.notes.isNotBlank()) {
                Text(
                    modele.notes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (modele.tier == ModelTier.HEAVY) {
                Text(
                    "⚠ Modele lourd : a n'essayer qu'apres avoir teste un modele 4B.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                installe -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Check, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Installe", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSupprimer) { Text("Supprimer") }
                }

                etat is DownloadState.Resolving -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  ${etat.message}", style = MaterialTheme.typography.bodySmall)
                }

                etat is DownloadState.Running -> Column {
                    LinearProgressIndicator(
                        progress = { etat.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            if (etat.bytesTotal > 0)
                                "${formatBytes(etat.bytesDone)} / ${formatBytes(etat.bytesTotal)}"
                            else formatBytes(etat.bytesDone),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${formatBytes(etat.bytesPerSecond)}/s",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onAnnuler) { Text("Annuler") }
                }

                etat is DownloadState.Failed -> Column {
                    Text(
                        etat.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onTelecharger, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Reessayer")
                    }
                }

                else -> Button(onClick = onTelecharger, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("  Telecharger (${modele.approxSizeLabel})")
                }
            }
        }
    }
}
