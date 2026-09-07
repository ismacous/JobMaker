package com.jobmaker.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jobmaker.render.CvTemplates
import com.jobmaker.ui.components.ApercuHtml
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.JaugeScore
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.DocumentsViewModel
import com.jobmaker.ui.vm.EvenementExport

private val onglets = listOf("CV", "Lettre", "Offre analysee", "Relecture")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidatureScreen(
    candidatureId: String,
    vm: DocumentsViewModel,
    onRetour: () -> Unit,
    onEditerCv: () -> Unit,
    onEditerLettre: () -> Unit,
    onRegenerer: (String) -> Unit,
) {
    LaunchedEffect(candidatureId) { vm.charger(candidatureId) }

    val candidature by vm.courante.collectAsState()
    val profil by vm.profile.collectAsState()
    val export by vm.export.collectAsState()
    val contexte = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var ongletActif by remember { mutableIntStateOf(0) }

    // Un export termine ouvre directement le selecteur de partage : c'est
    // toujours ce qu'on veut faire d'un CV en PDF.
    LaunchedEffect(export) {
        when (val e = export) {
            is EvenementExport.Pret -> {
                runCatching {
                    contexte.startActivity(
                        Intent.createChooser(
                            vm.intentPartage(e.fichier, e.typeMime, e.sujet),
                            "Envoyer ${e.fichier.name}",
                        )
                    )
                }.onFailure { snackbar.showSnackbar("Aucune application pour partager ce fichier.") }
                snackbar.showSnackbar("Fichier cree : ${e.fichier.name}")
                vm.consommerExport()
            }
            is EvenementExport.Erreur -> {
                snackbar.showSnackbar(e.message)
                vm.consommerExport()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        candidature?.analyse?.poste?.ifBlank { "Candidature" } ?: "Candidature",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    candidature?.let { c ->
                        IconButton(onClick = { onRegenerer(c.offreTexte) }) {
                            Icon(Icons.Default.Refresh, "Regenerer")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val c = candidature
        if (c == null) {
            Column(Modifier.padding(padding).fillMaxSize(), Arrangement.Center) {
                Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = ongletActif) {
                onglets.forEachIndexed { index, titre ->
                    Tab(
                        selected = ongletActif == index,
                        onClick = { ongletActif = index },
                        text = { Text(titre, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }

            when (ongletActif) {
                0 -> OngletCv(vm, onEditerCv, export is EvenementExport.EnCours)
                1 -> OngletLettre(vm, onEditerLettre, export is EvenementExport.EnCours)
                2 -> OngletAnalyse(c)
                3 -> OngletRelecture(c, vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun OngletCv(vm: DocumentsViewModel, onEditer: () -> Unit, exportEnCours: Boolean) {
    val candidature by vm.courante.collectAsState()
    val c = candidature ?: return
    val presse = LocalClipboardManager.current
    val gabarit = CvTemplates.byId(c.gabarit)

    Column(Modifier.fillMaxSize()) {
        // --- barre d'actions ---
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.exporterCvPdf() },
                enabled = !exportEnCours,
                modifier = Modifier.weight(1f),
            ) {
                if (exportEnCours) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                Text("  PDF")
            }
            OutlinedButton(onClick = onEditer) {
                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                Text("  Modifier")
            }
            OutlinedButton(onClick = {
                presse.setText(AnnotatedString(vm.texteCvPourCopie()))
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
            }
        }

        // --- choix du gabarit ---
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CvTemplates.tous.forEach { modele ->
                    FilterChip(
                        selected = modele.id == c.gabarit,
                        onClick = { vm.majGabarit(modele.id) },
                        label = { Text(modele.nom) },
                        leadingIcon = if (modele.id == c.gabarit) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CvTemplates.couleurs.forEach { (hex, nom) ->
                    val couleur = Color(android.graphics.Color.parseColor(hex))
                    Surface(
                        modifier = Modifier.size(if (hex == c.couleurAccent) 30.dp else 24.dp)
                            .clip(CircleShape),
                        color = couleur,
                        onClick = { vm.majCouleur(hex) },
                    ) {
                        if (hex == c.couleurAccent) {
                            Icon(
                                Icons.Default.Check, nom,
                                Modifier.size(14.dp).padding(2.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
            Text(
                if (gabarit.atsSafe)
                    "✓ ${gabarit.description}"
                else
                    "⚠ ${gabarit.description}",
                style = MaterialTheme.typography.bodySmall,
                color = if (gabarit.atsSafe) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }

        ApercuHtml(vm.htmlCv(), Modifier.fillMaxSize())
    }
}

@Composable
private fun OngletLettre(vm: DocumentsViewModel, onEditer: () -> Unit, exportEnCours: Boolean) {
    val presse = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.exporterLettrePdf() },
                enabled = !exportEnCours,
                modifier = Modifier.weight(1f),
            ) {
                if (exportEnCours) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                Text("  PDF")
            }
            OutlinedButton(onClick = onEditer) {
                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                Text("  Modifier")
            }
            OutlinedButton(onClick = {
                presse.setText(AnnotatedString(vm.texteLettrePourCopie()))
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
            }
        }
        Text(
            "Le texte copie sert pour les formulaires en ligne qui n'acceptent pas de fichier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        ApercuHtml(vm.htmlLettre(), Modifier.fillMaxSize())
    }
}

@Composable
private fun OngletAnalyse(c: com.jobmaker.data.model.Candidature) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        if (c.analyse.annonceIncomplete) {
            Bandeau(
                "L'annonce etait peu detaillee. L'analyse ci-dessous contient des deductions " +
                    "faites a partir du metier en general : verifiez-les avant l'entretien.",
                TypeBandeau.ALERTE,
            )
        }

        SectionCarte("Le poste", sousTitre = "Ce que l'analyse a retenu de l'annonce") {
            LigneInfo("Intitule", c.analyse.poste)
            LigneInfo("Entreprise", c.analyse.entreprise)
            LigneInfo("Lieu", c.analyse.lieu)
            LigneInfo("Contrat", c.analyse.contrat)
            LigneInfo("Secteur", c.analyse.secteur)
            LigneInfo("Niveau attendu", c.analyse.seniorite)
            LigneInfo("Remuneration", c.analyse.remuneration)
        }

        if (c.analyse.missions.isNotEmpty()) {
            SectionCarte("Missions") { ListePuces(c.analyse.missions) }
        }
        if (c.analyse.competencesRequises.isNotEmpty()) {
            SectionCarte("Exigences", sousTitre = "Sans ces elements, la candidature part mal") {
                ListePuces(c.analyse.competencesRequises)
            }
        }
        if (c.analyse.competencesSouhaitees.isNotEmpty()) {
            SectionCarte("Souhaite (pas indispensable)", replierParDefaut = true) {
                ListePuces(c.analyse.competencesSouhaitees)
            }
        }
        if (c.analyse.attentesImplicites.isNotEmpty()) {
            SectionCarte(
                "Ce que l'annonce n'ecrit pas",
                sousTitre = "Lecture entre les lignes",
            ) { ListePuces(c.analyse.attentesImplicites) }
        }
        if (c.analyse.motsClesAts.isNotEmpty()) {
            SectionCarte(
                "Mots-cles a replacer",
                sousTitre = "Les filtres automatiques cherchent ces termes exacts",
            ) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    c.analyse.motsClesAts.forEach {
                        AssistChip(onClick = {}, label = { Text(it) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                }
            }
        }

        SectionCarte("Strategie retenue", replierParDefaut = true) {
            LigneInfo("Angle", c.strategie.angle)
            LigneInfo("Ton", c.strategie.ton)
            if (c.strategie.ecarts.isNotEmpty()) {
                Text("Ecarts identifies et reponses :",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp))
                c.strategie.ecarts.forEach { ecart ->
                    Text("• ${ecart.ecart}", style = MaterialTheme.typography.bodyMedium)
                    Text("   → ${ecart.reponse}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (c.strategie.argumentsCles.isNotEmpty()) {
                Text("Arguments pour l'entretien :",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp))
                ListePuces(c.strategie.argumentsCles)
            }
        }

        SectionCarte("Texte original de l'offre", replierParDefaut = true) {
            Text(c.offreTexte, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OngletRelecture(c: com.jobmaker.data.model.Candidature, vm: DocumentsViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                JaugeScore(c.revue.scoreGlobal, "Note de la relecture")
                Spacer(Modifier.height(10.dp))
                JaugeScore(c.scoreAts, "Couverture des mots-cles de l'offre")
                if (c.revue.verdict.isNotBlank()) {
                    Text(
                        c.revue.verdict,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        if (c.revue.faitsInventes.isNotEmpty()) {
            Bandeau(
                "Elements presents dans les documents mais introuvables dans votre profil. " +
                    "Corrigez-les avant d'envoyer : un chiffre ou un employeur inexact se " +
                    "retourne contre vous en entretien.",
                TypeBandeau.ERREUR,
            )
            SectionCarte("A verifier absolument") {
                ListePuces(c.revue.faitsInventes)
            }
        }

        if (c.revue.motsClesManquants.isNotEmpty()) {
            SectionCarte(
                "Mots-cles de l'offre absents du CV",
                sousTitre = "A ajouter uniquement si vous les maitrisez vraiment",
            ) { ListePuces(c.revue.motsClesManquants) }
        }

        val parGravite = c.revue.problemes.groupBy { it.gravite.lowercase() }
        listOf("bloquant", "important", "mineur").forEach { gravite ->
            val liste = parGravite[gravite].orEmpty()
            if (liste.isEmpty()) return@forEach
            SectionCarte(
                when (gravite) {
                    "bloquant" -> "Bloquant"
                    "important" -> "Important"
                    else -> "Details"
                },
                replierParDefaut = gravite == "mineur",
            ) {
                liste.forEach { probleme ->
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Text("${probleme.zone} : ${probleme.probleme}",
                            style = MaterialTheme.typography.bodyMedium)
                        if (probleme.correction.isNotBlank()) {
                            Text("→ ${probleme.correction}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (c.revue.pointsForts.isNotEmpty()) {
            SectionCarte("Ce qui fonctionne", replierParDefaut = true) {
                ListePuces(c.revue.pointsForts)
            }
        }

        SectionCarte("Notes personnelles", sousTitre = "Pour vous, jamais imprimees") {
            androidx.compose.material3.OutlinedTextField(
                value = c.notesPerso,
                onValueChange = vm::majNotes,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("Date d'envoi, contact, relance prevue...") },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun LigneInfo(libelle: String, valeur: String) {
    if (valeur.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            libelle,
            Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(valeur, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListePuces(items: List<String>) {
    Column {
        items.filter { it.isNotBlank() }.forEach {
            Text("•  $it", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}
