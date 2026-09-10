package com.jobmaker.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobmaker.llm.CompteursSysteme
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.TexteDefilant
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.GenerateViewModel
import com.jobmaker.work.PhaseGeneration
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

    // Android 13 et au-dela : sans cette permission, la notification
    // d'avancement n'apparait pas. La generation, elle, tourne quand meme.
    val demandeNotifs = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val demanderNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            demandeNotifs.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
                    onClick = {
                        // La generation tourne desormais dans un service : elle
                        // continue meme si l'on quitte l'application. La
                        // permission ne sert qu'a en afficher l'avancement.
                        demanderNotifications()
                        vm.lancer()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = offre.isNotBlank() && modeles.isNotEmpty(),
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp))
                    Text("  Generer mon CV et ma lettre")
                }
                Text(
                    "Tout se passe sur le telephone, et un telephone ecrit lentement : " +
                        "comptez une dizaine de minutes. Vous pouvez quitter l'application, " +
                        "la generation continue et vous previent quand c'est pret. Le bilan " +
                        "affiche a la fin dit ou le temps est passe.",
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

            // Uniquement une fois la generation finie : construire ce texte a
            // chaque rafraichissement pendant qu'elle tourne ne servirait a rien.
            if (!etat.enCours) BilanGeneration(etat.rapport())

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ProgressionGeneration(vm: GenerateViewModel) {
    val etat by vm.etat.collectAsState()

    // Une generation represente plusieurs minutes de calcul : un appui
    // involontaire ne doit pas pouvoir les effacer.
    var demandeInterruption by remember { mutableStateOf(false) }
    if (demandeInterruption) {
        AlertDialog(
            onDismissRequest = { demandeInterruption = false },
            title = { Text("Interrompre la generation ?") },
            text = {
                Text(
                    "Le travail deja fait sera perdu et il faudra tout recommencer " +
                        "depuis le debut."
                )
            },
            confirmButton = {
                TextButton(onClick = { demandeInterruption = false; vm.annuler() }) {
                    Text("Interrompre")
                }
            },
            dismissButton = {
                TextButton(onClick = { demandeInterruption = false }) { Text("Continuer") }
            },
        )
    }

    // Horloge qui avance : sans elle, impossible de savoir si le modele
    // travaille ou si l'application est bloquee.
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Nombre de coeurs reellement occupes, releve en direct. Le banc d'essai
    // donne cette valeur sur un prompt fabrique ; ici elle repond sur la vraie
    // generation, la seule qui rame. Proche du nombre de threads : le
    // telephone calcule. Proche de zero : il attend.
    var coeursOccupes by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(etat.enCours) {
        var precedent = CompteursSysteme.lire()
        var precedentMs = System.currentTimeMillis()
        while (etat.enCours) {
            delay(500)
            val maintenantMs = System.currentTimeMillis()
            val actuel = CompteursSysteme.lire()
            val ecouleMs = maintenantMs - precedentMs
            if (ecouleMs > 0) {
                coeursOccupes = (actuel - precedent).msProcesseur.toDouble() / ecouleMs
            }
            precedent = actuel
            precedentMs = maintenantMs
            maintenant = maintenantMs
        }
    }

    // L'ecran reste allume tant qu'on regarde la generation. Ce n'est plus
    // indispensable -- le service de premier plan empeche la mise en veille du
    // calcul -- mais cela evite d'avoir a deverrouiller pour suivre l'avancee.
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    dureeCourte(ecoule),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "%.1f coeurs".format(coeursOccupes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            onClick = { demandeInterruption = true },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("Interrompre") }
    }
}

/**
 * Bilan chiffre de la generation qui vient de finir, replie par defaut.
 *
 * Une lenteur ne se corrige pas sur une impression : il faut savoir laquelle
 * des deux phases coute, combien de tokens ont ete ecrits pour de vrai, et
 * pourquoi chaque etape s'est arretee. Le bouton de copie existe pour que ces
 * chiffres puissent quitter le telephone tels quels.
 */
@Composable
private fun BilanGeneration(rapport: String) {
    if (rapport.isBlank()) return
    val presse = LocalClipboardManager.current

    Spacer(Modifier.height(8.dp))
    SectionCarte(
        titre = "Details techniques",
        sousTitre = "Ou est passe le temps",
        replierParDefaut = true,
    ) {
        Text(
            rapport,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        OutlinedButton(
            onClick = { presse.setText(AnnotatedString(rapport)) },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Copier le bilan") }
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
