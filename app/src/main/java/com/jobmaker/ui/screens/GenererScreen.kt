package com.jobmaker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.TexteDefilant
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.GenerateViewModel
import com.jobmaker.ui.vm.PhaseGeneration
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenererScreen(
    vm: GenerateViewModel,
    onVoirCandidature: (String) -> Unit,
    onOuvrirProfil: () -> Unit,
    onOuvrirModeles: () -> Unit,
    onOuvrirReglages: () -> Unit,
) {
    val offre by vm.offre.collectAsState()
    val etat by vm.etat.collectAsState()
    val profil by vm.profile.collectAsState()
    val modeles by vm.modelesInstalles.collectAsState()
    val presse = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle candidature") },
                actions = {
                    IconButton(onClick = onOuvrirReglages) {
                        Icon(Icons.Default.Settings, "Reglages")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            // --- prerequis ---
            if (modeles.isEmpty()) {
                Bandeau(
                    "Aucun modele d'IA n'est installe. Sans modele, l'application ne peut " +
                        "rien generer. Le telechargement se fait une seule fois, en Wi-Fi.",
                    TypeBandeau.ERREUR,
                ) {
                    Button(onClick = onOuvrirModeles) { Text("Telecharger un modele") }
                }
            }

            if (profil.completude < 50) {
                Bandeau(
                    "Votre profil est rempli a ${profil.completude} %. Plus il est complet, " +
                        "plus le CV genere sera precis : l'IA ne peut mettre en avant que ce " +
                        "que vous lui avez donne.",
                    TypeBandeau.ALERTE,
                ) {
                    Button(onClick = onOuvrirProfil) { Text("Completer mon profil") }
                }
            }

            Spacer(Modifier.height(6.dp))

            // --- saisie de l'offre ---
            Text(
                "Collez l'offre d'emploi",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                "Copiez tout le texte de l'annonce : intitule, missions, profil recherche, " +
                    "nom de l'entreprise. Meme une annonce de trois lignes fonctionne, " +
                    "l'analyse completera avec ce que le metier implique habituellement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = offre,
                onValueChange = vm::majOffre,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                minLines = 8,
                maxLines = 18,
                placeholder = { Text("Texte de l'annonce...") },
                shape = MaterialTheme.shapes.small,
                enabled = !etat.enCours,
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        presse.getText()?.text?.takeIf { it.isNotBlank() }?.let(vm::majOffre)
                    },
                    enabled = !etat.enCours,
                ) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                    Text("  Coller")
                }
                if (offre.isNotBlank()) {
                    TextButton(onClick = { vm.majOffre("") }, enabled = !etat.enCours) {
                        Text("Effacer")
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${offre.length} caracteres",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- lancement ---
            if (!etat.enCours) {
                Button(
                    onClick = { vm.lancer() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = offre.isNotBlank() && modeles.isNotEmpty(),
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp))
                    Text("  Generer mon CV et ma lettre")
                }
                Text(
                    "Tout se passe sur le telephone : comptez 2 a 10 minutes selon le modele " +
                        "choisi. Vous pouvez laisser l'ecran allume et attendre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                ProgressionGeneration(vm)
            }

            // --- resultats / erreurs ---
            etat.erreur?.let { erreur ->
                Spacer(Modifier.height(8.dp))
                Bandeau(erreur, TypeBandeau.ERREUR) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.reinitialiser() }) { Text("Fermer") }
                        OutlinedButton(onClick = onOuvrirReglages) { Text("Reglages") }
                    }
                }
            }

            etat.avertissements.forEach { avertissement ->
                Bandeau(avertissement, TypeBandeau.ALERTE)
            }

            etat.candidatureId?.let { id ->
                Spacer(Modifier.height(8.dp))
                Bandeau(
                    "Candidature prete. Relisez-la avant d'envoyer : c'est votre nom qui part, " +
                        "pas celui de l'IA.",
                    TypeBandeau.SUCCES,
                ) {
                    Button(onClick = {
                        vm.reinitialiser()
                        onVoirCandidature(id)
                    }) { Text("Ouvrir le CV et la lettre") }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ProgressionGeneration(vm: GenerateViewModel) {
    val etat by vm.etat.collectAsState()

    // Horloge qui avance : sans elle, impossible de savoir si le modele
    // travaille ou si l'application est bloquee.
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(etat.enCours) {
        while (etat.enCours) {
            maintenant = System.currentTimeMillis()
            delay(500)
        }
    }

    // L'ecran reste allume pendant la generation : l'ecran eteint fait
    // ralentir puis suspendre le calcul par Android.
    val vue = LocalView.current
    DisposableEffect(etat.enCours) {
        vue.keepScreenOn = etat.enCours
        onDispose { vue.keepScreenOn = false }
    }

    val ecoule = if (etat.debutMs == 0L) 0L else maintenant - etat.debutMs

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    "Etape ${etat.etapeIndex}/${etat.etapesTotal} — ${etat.etapeTitre}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (etat.etapeDetail.isNotBlank()) {
                    Text(
                        etat.etapeDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                dureeCourte(ecoule),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LinearProgressIndicator(
            progress = { etat.progression },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

        // --- ce que fait le modele, en clair ---
        Spacer(Modifier.height(10.dp))
        Text(etat.phase.libelle, style = MaterialTheme.typography.titleSmall)

        when (etat.phase) {
            PhaseGeneration.LECTURE -> {
                LinearProgressIndicator(
                    progress = { etat.progressionLecture },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "${etat.promptLus} / ${etat.promptTotal} tokens" +
                        if (etat.vitesseLecture > 0)
                            "  ·  %.1f tokens/s".format(etat.vitesseLecture)
                        else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            PhaseGeneration.REDACTION -> {
                val vitesse = etat.vitesseRedaction(maintenant)
                Text(
                    "${etat.tokensEcrits} tokens ecrits" +
                        if (vitesse > 0) "  ·  %.1f tokens/s".format(vitesse) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (etat.msLecturePrompt > 0) {
                    Text(
                        "Prompt lu en ${dureeCourte(etat.msLecturePrompt)} " +
                            "(${etat.promptTotal} tokens)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PhaseGeneration.PREPARATION -> {
                Text(
                    "Le modele est mis en memoire. Quelques secondes la premiere fois.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (etat.modeleActuel.isNotBlank()) {
            Text(
                "Modele : ${etat.modeleActuel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (etat.apercuBrut.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Ce que le modele ecrit en ce moment :",
                style = MaterialTheme.typography.labelMedium,
            )
            TexteDefilant(etat.apercuBrut, Modifier.height(120.dp).padding(top = 4.dp))
        }

        OutlinedButton(
            onClick = { vm.annuler() },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("Interrompre") }
    }
}

/** Duree lisible : 42 s, 3 min 07 s, 1 h 05 min. */
private fun dureeCourte(ms: Long): String {
    val totalSecondes = (ms / 1000).coerceAtLeast(0)
    val heures = totalSecondes / 3600
    val minutes = (totalSecondes % 3600) / 60
    val secondes = totalSecondes % 60
    return when {
        heures > 0 -> "%d h %02d min".format(heures, minutes)
        minutes > 0 -> "%d min %02d s".format(minutes, secondes)
        else -> "$secondes s"
    }
}
