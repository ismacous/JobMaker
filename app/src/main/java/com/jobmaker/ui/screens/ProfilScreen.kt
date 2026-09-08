package com.jobmaker.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jobmaker.data.model.Experience
import com.jobmaker.data.model.Formation
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.Champ
import com.jobmaker.ui.components.JaugeScore
import com.jobmaker.ui.components.ListeChaines
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.remplacer
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * L'ecran ou tout commence.
 *
 * Le principe de l'application : on remplit ce formulaire une seule fois, en
 * detail, et il sert ensuite a produire autant de CV que d'offres. C'est donc
 * ici qu'il faut etre genereux -- le modele ne peut mettre en avant que ce
 * qu'il trouve dans cette page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilScreen(
    vm: ProfileViewModel,
    onOuvrirReglages: () -> Unit,
) {
    val profil by vm.profile.collectAsState()
    val enregistre by vm.enregistre.collectAsState()
    val contexte = LocalContext.current
    val portee = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val choixPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        portee.launch {
            val chemin = copierPhoto(contexte, uri)
            if (chemin != null) vm.majIdentite { it.copy(photoUri = chemin) }
            else snackbar.showSnackbar("Photo illisible.")
        }
    }

    val exportProfil = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        portee.launch {
            runCatching {
                val json = vm.exporterJson()
                withContext(Dispatchers.IO) {
                    contexte.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    }
                }
            }.onSuccess { snackbar.showSnackbar("Profil sauvegarde.") }
                .onFailure { snackbar.showSnackbar("Sauvegarde impossible.") }
        }
    }

    val importProfil = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        portee.launch {
            val texte = withContext(Dispatchers.IO) {
                runCatching {
                    contexte.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (texte == null) { snackbar.showSnackbar("Fichier illisible."); return@launch }
            vm.importerJson(texte)
                .onSuccess { snackbar.showSnackbar("Profil restaure.") }
                .onFailure { snackbar.showSnackbar("Ce fichier n'est pas une sauvegarde JobMaker.") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mon profil") },
                actions = {
                    Text(
                        if (enregistre) "Enregistre" else "...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onOuvrirReglages) {
                        Icon(Icons.Default.Settings, "Reglages")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // --- etat de completude ---
            Box(Modifier.padding(top = 8.dp)) {
                JaugeScore(profil.completude, "Profil rempli")
            }
            if (profil.manques.isNotEmpty()) {
                // A 100 % le profil est utilisable : ce qui reste n'est plus un
                // manque mais un gain possible. Dire « il reste a renseigner »
                // au-dessus d'un « 100 / 100 » serait contradictoire.
                val complet = profil.completude >= 100
                Bandeau(
                    (if (complet) "Profil utilisable. Pour aller plus loin :\n"
                    else "Il reste a renseigner :\n") +
                        profil.manques.joinToString("\n") { "• $it" },
                    if (complet) TypeBandeau.INFO else TypeBandeau.ALERTE,
                )
            } else {
                Bandeau(
                    "Profil complet. Vous pouvez maintenant coller des offres a volonte.",
                    TypeBandeau.SUCCES,
                )
            }

            // --- identite ---
            SectionCarte("Identite", sousTitre = "Reprise telle quelle sur tous vos documents") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Champ(profil.identite.prenom, { v -> vm.majIdentite { it.copy(prenom = v) } },
                        "Prenom", Modifier.weight(1f), obligatoire = true)
                    Champ(profil.identite.nom, { v -> vm.majIdentite { it.copy(nom = v) } },
                        "Nom", Modifier.weight(1f), obligatoire = true)
                }
                Champ(profil.identite.titre, { v -> vm.majIdentite { it.copy(titre = v) } },
                    "Intitule professionnel actuel",
                    aide = "Exemple : magasinier, aide-soignante, developpeur web. " +
                        "Sert de repli si l'IA n'en propose pas.")
                Champ(profil.identite.telephone, { v -> vm.majIdentite { it.copy(telephone = v) } },
                    "Telephone", clavier = KeyboardType.Phone, obligatoire = true)
                Champ(profil.identite.email, { v -> vm.majIdentite { it.copy(email = v) } },
                    "Email", clavier = KeyboardType.Email, obligatoire = true)
                Champ(profil.identite.adresse, { v -> vm.majIdentite { it.copy(adresse = v) } },
                    "Adresse")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Champ(profil.identite.codePostal,
                        { v -> vm.majIdentite { it.copy(codePostal = v) } },
                        "Code postal", Modifier.weight(0.4f), clavier = KeyboardType.Number)
                    Champ(profil.identite.ville, { v -> vm.majIdentite { it.copy(ville = v) } },
                        "Ville", Modifier.weight(0.6f), obligatoire = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Champ(profil.identite.dateNaissance,
                        { v -> vm.majIdentite { it.copy(dateNaissance = v) } },
                        "Date de naissance", Modifier.weight(1f),
                        aide = "Facultatif")
                    Champ(profil.identite.nationalite,
                        { v -> vm.majIdentite { it.copy(nationalite = v) } },
                        "Nationalite", Modifier.weight(1f), aide = "Facultatif")
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        choixPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Text(if (profil.identite.photoUri.isBlank()) "Ajouter une photo"
                        else "Changer la photo")
                    }
                    if (profil.identite.photoUri.isNotBlank()) {
                        TextButton(onClick = { vm.majIdentite { it.copy(photoUri = "") } }) {
                            Text("Retirer")
                        }
                    }
                }
                Text(
                    "En France, la photo n'est pas attendue et peut introduire un biais. " +
                        "Elle reste utile dans le commerce, l'hotellerie et le contact client. " +
                        "Son affichage s'active gabarit par gabarit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- presentation ---
            SectionCarte(
                "Presentation libre",
                sousTitre = "La partie la plus utile de cette page",
            ) {
                Champ(
                    profil.presentation, vm::majPresentation,
                    "Parlez de vous, avec vos mots", lignes = 6,
                    aide = "Ce que vous savez faire, ce que vous aimez, votre facon de travailler, " +
                        "vos contraintes. Ecrivez comme vous parlez : l'IA reformule. " +
                        "Plus vous en dites, plus l'accroche du CV sera juste.",
                )
                Champ(
                    profil.objectifPro, vm::majObjectif,
                    "Ce que vous cherchez", lignes = 3,
                    aide = "Type de poste, de contrat, de secteur.",
                )
            }

            // --- experiences ---
            SectionCarte(
                "Experiences",
                sousTitre = "${profil.experiences.size} experience(s). Jobs d'ete, interim, " +
                    "stages : tout compte.",
                actionTitre = "Ajouter une experience",
                onAction = vm::ajouterExperience,
            ) {
                profil.experiences.forEachIndexed { index, experience ->
                    BlocExperienceProfil(
                        experience = experience,
                        premier = index == 0,
                        dernier = index == profil.experiences.lastIndex,
                        onChange = { bloc -> vm.majExperience(experience.id, bloc) },
                        onSupprimer = { vm.supprimerExperience(experience.id) },
                        onDeplacer = { delta -> vm.deplacerExperience(experience.id, delta) },
                    )
                    if (index != profil.experiences.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
                if (profil.experiences.isEmpty()) {
                    Text(
                        "Aucune experience pour le moment. Ajoutez-en une, meme courte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- formations ---
            SectionCarte(
                "Parcours scolaire",
                sousTitre = "${profil.formations.size} formation(s)",
                actionTitre = "Ajouter une formation",
                onAction = vm::ajouterFormation,
            ) {
                profil.formations.forEachIndexed { index, formation ->
                    BlocFormationProfil(
                        formation = formation,
                        premier = index == 0,
                        dernier = index == profil.formations.lastIndex,
                        onChange = { bloc -> vm.majFormation(formation.id, bloc) },
                        onSupprimer = { vm.supprimerFormation(formation.id) },
                        onDeplacer = { delta -> vm.deplacerFormation(formation.id, delta) },
                    )
                    if (index != profil.formations.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            // --- competences ---
            SectionCarte(
                "Competences",
                sousTitre = "Regroupez-les : \"Techniques\", \"Logiciels\", \"Relationnel\"...",
            ) {
                profil.competences.forEach { groupe ->
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Champ(groupe.categorie,
                                { v -> vm.majGroupeCompetences(groupe.id) { it.copy(categorie = v) } },
                                "Categorie", Modifier.weight(1f))
                            IconButton(onClick = { vm.supprimerGroupeCompetences(groupe.id) }) {
                                Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp))
                            }
                        }
                        ListeChaines(
                            titre = "Competences",
                            valeurs = groupe.items.map { it.nom },
                            onAjouter = { vm.ajouterCompetence(groupe.id, it) },
                            onSupprimer = { vm.supprimerCompetence(groupe.id, it) },
                            onModifier = { i, v -> vm.majNomCompetence(groupe.id, i, v) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Techniques", "Logiciels", "Relationnel", "Langues techniques")
                        .forEach { suggestion ->
                            if (profil.competences.none { it.categorie == suggestion }) {
                                OutlinedButton(
                                    onClick = { vm.ajouterGroupeCompetences(suggestion) },
                                ) { Text(suggestion, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                }
            }

            // --- langues ---
            SectionCarte(
                "Langues",
                actionTitre = "Ajouter une langue",
                onAction = vm::ajouterLangue,
            ) {
                profil.langues.forEach { langue ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Champ(langue.nom, { v -> vm.majLangue(langue.id) { it.copy(nom = v) } },
                            "Langue", Modifier.weight(1f))
                        Champ(langue.niveau, { v -> vm.majLangue(langue.id) { it.copy(niveau = v) } },
                            "Niveau", Modifier.weight(1f),
                            aide = "Langue maternelle, C1, B2...")
                        IconButton(onClick = { vm.supprimerLangue(langue.id) }) {
                            Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp))
                        }
                    }
                }
            }

            // --- certifications ---
            SectionCarte(
                "Certifications et permis",
                replierParDefaut = true,
                actionTitre = "Ajouter une certification",
                onAction = vm::ajouterCertification,
            ) {
                profil.certifications.forEach { certification ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Champ(certification.nom,
                            { v -> vm.majCertification(certification.id) { it.copy(nom = v) } },
                            "Intitule", Modifier.weight(1f))
                        Champ(certification.annee,
                            { v -> vm.majCertification(certification.id) { it.copy(annee = v) } },
                            "Annee", Modifier.weight(0.4f))
                        IconButton(onClick = { vm.supprimerCertification(certification.id) }) {
                            Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                ListeChaines(
                    titre = "Permis",
                    aide = "Exemple : B, C, CACES 1-3-5, AIPR",
                    valeurs = profil.permis,
                    onAjouter = { vm.majPermis(profil.permis + it) },
                    onSupprimer = { i -> vm.majPermis(profil.permis.filterIndexed { j, _ -> j != i }) },
                    onModifier = { i, v -> vm.majPermis(profil.permis.remplacer(i, v)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = profil.recherche.vehicule,
                        onCheckedChange = { v -> vm.majRecherche { it.copy(vehicule = v) } },
                    )
                    Text("Je dispose d'un vehicule personnel")
                }
            }

            // --- projets et benevolat ---
            SectionCarte(
                "Projets",
                replierParDefaut = true,
                sousTitre = "Utile quand l'experience professionnelle manque",
                actionTitre = "Ajouter un projet",
                onAction = vm::ajouterProjet,
            ) {
                profil.projets.forEach { projet ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Champ(projet.nom, { v -> vm.majProjet(projet.id) { it.copy(nom = v) } },
                            "Nom")
                        Champ(projet.description,
                            { v -> vm.majProjet(projet.id) { it.copy(description = v) } },
                            "Description", lignes = 2)
                        TextButton(onClick = { vm.supprimerProjet(projet.id) }) { Text("Supprimer") }
                    }
                }
            }

            SectionCarte(
                "Benevolat et associatif",
                replierParDefaut = true,
                sousTitre = "Compte comme une experience, surtout en debut de parcours",
                actionTitre = "Ajouter",
                onAction = vm::ajouterBenevolat,
            ) {
                profil.benevolat.forEach { activite ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Champ(activite.poste,
                            { v -> vm.majBenevolat(activite.id) { it.copy(poste = v) } }, "Role")
                        Champ(activite.entreprise,
                            { v -> vm.majBenevolat(activite.id) { it.copy(entreprise = v) } },
                            "Organisme")
                        ListeChaines(
                            titre = "Ce que vous y faisiez",
                            valeurs = activite.missions,
                            onAjouter = { v ->
                                vm.majBenevolat(activite.id) { it.copy(missions = it.missions + v) }
                            },
                            onSupprimer = { i ->
                                vm.majBenevolat(activite.id) {
                                    it.copy(missions = it.missions.filterIndexed { j, _ -> j != i })
                                }
                            },
                            onModifier = { i, v ->
                                vm.majBenevolat(activite.id) {
                                    it.copy(missions = it.missions.remplacer(i, v))
                                }
                            },
                        )
                        TextButton(onClick = { vm.supprimerBenevolat(activite.id) }) {
                            Text("Supprimer")
                        }
                    }
                }
            }

            // --- liens ---
            SectionCarte(
                "Liens",
                replierParDefaut = true,
                actionTitre = "Ajouter un lien",
                onAction = vm::ajouterLien,
            ) {
                profil.liens.forEach { lien ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Champ(lien.libelle, { v -> vm.majLien(lien.id) { it.copy(libelle = v) } },
                            "Libelle", Modifier.weight(0.4f))
                        Champ(lien.url, { v -> vm.majLien(lien.id) { it.copy(url = v) } },
                            "Adresse", Modifier.weight(0.6f), clavier = KeyboardType.Uri)
                        IconButton(onClick = { vm.supprimerLien(lien.id) }) {
                            Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp))
                        }
                    }
                }
            }

            // --- contexte de recherche ---
            SectionCarte(
                "Contexte de recherche",
                sousTitre = "Ne s'imprime pas sur le CV, mais oriente la redaction",
                replierParDefaut = true,
            ) {
                Champ(profil.recherche.posteVise,
                    { v -> vm.majRecherche { it.copy(posteVise = v) } }, "Poste recherche")
                Champ(profil.recherche.disponibilite,
                    { v -> vm.majRecherche { it.copy(disponibilite = v) } },
                    "Disponibilite", aide = "Immediate, sous un mois, a partir de septembre...")
                Champ(profil.recherche.mobilite,
                    { v -> vm.majRecherche { it.copy(mobilite = v) } },
                    "Mobilite", aide = "Rayon, villes acceptees, demenagement possible")
                Champ(profil.recherche.teletravail,
                    { v -> vm.majRecherche { it.copy(teletravail = v) } }, "Teletravail")
                Champ(profil.recherche.pretentionsSalariales,
                    { v -> vm.majRecherche { it.copy(pretentionsSalariales = v) } },
                    "Pretentions salariales",
                    aide = "Ne sera pas ecrit sur le CV, sauf si l'annonce le demande")
                Champ(profil.recherche.contraintes,
                    { v -> vm.majRecherche { it.copy(contraintes = v) } },
                    "Contraintes", lignes = 2,
                    aide = "Horaires impossibles, sante, garde d'enfants. L'IA en tiendra compte " +
                        "sans les mentionner.")
                Spacer(Modifier.height(6.dp))
                ListeChaines(
                    titre = "Centres d'interet",
                    valeurs = profil.centresInteret,
                    onAjouter = { vm.majCentresInteret(profil.centresInteret + it) },
                    onSupprimer = { i ->
                        vm.majCentresInteret(profil.centresInteret.filterIndexed { j, _ -> j != i })
                    },
                    onModifier = { i, v -> vm.majCentresInteret(profil.centresInteret.remplacer(i, v)) },
                )
            }

            // --- sauvegarde ---
            SectionCarte(
                "Sauvegarde du profil",
                sousTitre = "Tout est stocke sur ce telephone uniquement. " +
                    "Exportez un fichier pour ne rien perdre en cas de changement d'appareil.",
                replierParDefaut = true,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportProfil.launch("profil-jobmaker.json") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Exporter") }
                    OutlinedButton(
                        onClick = { importProfil.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Restaurer") }
                }
            }

            Spacer(Modifier.height(34.dp))
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun BlocExperienceProfil(
    experience: Experience,
    premier: Boolean,
    dernier: Boolean,
    onChange: ((Experience) -> Experience) -> Unit,
    onSupprimer: () -> Unit,
    onDeplacer: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                experience.resumeLigne.ifBlank { "Nouvelle experience" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { onDeplacer(-1) }, enabled = !premier) {
                Icon(Icons.Default.ArrowUpward, "Monter", Modifier.size(18.dp))
            }
            IconButton(onClick = { onDeplacer(1) }, enabled = !dernier) {
                Icon(Icons.Default.ArrowDownward, "Descendre", Modifier.size(18.dp))
            }
            IconButton(onClick = onSupprimer) {
                Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp))
            }
        }
        Champ(experience.poste, { v -> onChange { it.copy(poste = v) } }, "Poste occupe")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ(experience.entreprise, { v -> onChange { it.copy(entreprise = v) } },
                "Entreprise", Modifier.weight(1f))
            Champ(experience.lieu, { v -> onChange { it.copy(lieu = v) } },
                "Lieu", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ(experience.typeContrat, { v -> onChange { it.copy(typeContrat = v) } },
                "Contrat", Modifier.weight(1f), aide = "CDI, CDD, interim, stage")
            Champ(experience.secteur, { v -> onChange { it.copy(secteur = v) } },
                "Secteur", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ(experience.dateDebut, { v -> onChange { it.copy(dateDebut = v) } },
                "Debut", Modifier.weight(1f), aide = "mars 2022")
            Champ(experience.dateFin, { v -> onChange { it.copy(dateFin = v) } },
                "Fin", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = experience.enCours,
                onCheckedChange = { v -> onChange { it.copy(enCours = v) } },
            )
            Text("Poste toujours en cours")
        }
        Champ(experience.contexte, { v -> onChange { it.copy(contexte = v) } },
            "Contexte", lignes = 2,
            aide = "Taille de l'equipe, volume traite, budget. Ce sont ces details qui " +
                "rendent une experience credible.")
        Spacer(Modifier.height(4.dp))
        ListeChaines(
            titre = "Ce que vous faisiez",
            aide = "Une tache par ligne, sans chercher a bien ecrire : l'IA reformulera.",
            valeurs = experience.missions,
            multiligne = true,
            onAjouter = { v -> onChange { it.copy(missions = it.missions + v) } },
            onSupprimer = { i ->
                onChange { it.copy(missions = it.missions.filterIndexed { j, _ -> j != i }) }
            },
            onModifier = { i, v -> onChange { it.copy(missions = it.missions.remplacer(i, v)) } },
        )
        Spacer(Modifier.height(6.dp))
        ListeChaines(
            titre = "Resultats obtenus",
            aide = "Avec des chiffres si vous en avez : nombre de clients, tonnage, delai " +
                "gagne, note de satisfaction. C'est ce qui fait la difference sur un CV, " +
                "et l'IA n'a pas le droit d'en inventer.",
            valeurs = experience.realisations,
            multiligne = true,
            onAjouter = { v -> onChange { it.copy(realisations = it.realisations + v) } },
            onSupprimer = { i ->
                onChange { it.copy(realisations = it.realisations.filterIndexed { j, _ -> j != i }) }
            },
            onModifier = { i, v -> onChange { it.copy(realisations = it.realisations.remplacer(i, v)) } },
        )
        Spacer(Modifier.height(6.dp))
        ListeChaines(
            titre = "Outils, machines, logiciels",
            valeurs = experience.outils,
            onAjouter = { v -> onChange { it.copy(outils = it.outils + v) } },
            onSupprimer = { i ->
                onChange { it.copy(outils = it.outils.filterIndexed { j, _ -> j != i }) }
            },
            onModifier = { i, v -> onChange { it.copy(outils = it.outils.remplacer(i, v)) } },
        )
    }
}

@Composable
private fun BlocFormationProfil(
    formation: Formation,
    premier: Boolean,
    dernier: Boolean,
    onChange: ((Formation) -> Formation) -> Unit,
    onSupprimer: () -> Unit,
    onDeplacer: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formation.diplome.ifBlank { "Nouvelle formation" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { onDeplacer(-1) }, enabled = !premier) {
                Icon(Icons.Default.ArrowUpward, "Monter", Modifier.size(18.dp))
            }
            IconButton(onClick = { onDeplacer(1) }, enabled = !dernier) {
                Icon(Icons.Default.ArrowDownward, "Descendre", Modifier.size(18.dp))
            }
            IconButton(onClick = onSupprimer) {
                Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp))
            }
        }
        Champ(formation.diplome, { v -> onChange { it.copy(diplome = v) } },
            "Diplome ou formation")
        Champ(formation.etablissement, { v -> onChange { it.copy(etablissement = v) } },
            "Etablissement")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ(formation.lieu, { v -> onChange { it.copy(lieu = v) } },
                "Lieu", Modifier.weight(1f))
            Champ(formation.niveau, { v -> onChange { it.copy(niveau = v) } },
                "Niveau", Modifier.weight(1f), aide = "CAP, Bac, Bac+2...")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ(formation.dateDebut, { v -> onChange { it.copy(dateDebut = v) } },
                "Debut", Modifier.weight(1f))
            Champ(formation.dateFin, { v -> onChange { it.copy(dateFin = v) } },
                "Fin", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = formation.enCours,
                onCheckedChange = { v -> onChange { it.copy(enCours = v) } },
            )
            Text("Formation en cours")
        }
        Champ(formation.mention, { v -> onChange { it.copy(mention = v) } }, "Mention")
        ListeChaines(
            titre = "Matieres ou specialites",
            valeurs = formation.matieres,
            onAjouter = { v -> onChange { it.copy(matieres = it.matieres + v) } },
            onSupprimer = { i ->
                onChange { it.copy(matieres = it.matieres.filterIndexed { j, _ -> j != i }) }
            },
            onModifier = { i, v -> onChange { it.copy(matieres = it.matieres.remplacer(i, v)) } },
        )
    }
}

/**
 * Copie la photo choisie dans le stockage prive de l'application.
 *
 * Le selecteur de medias d'Android ne donne qu'une autorisation temporaire sur
 * l'URI : garder cette URI dans le profil ferait disparaitre la photo au
 * prochain lancement. On en fait donc une copie definitive.
 */
private suspend fun copierPhoto(contexte: Context, source: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val cible = File(contexte.filesDir, "photo_profil.jpg")
            contexte.contentResolver.openInputStream(source)?.use { entree ->
                cible.outputStream().use { sortie -> entree.copyTo(sortie) }
            } ?: return@runCatching null
            "file://${cible.absolutePath}"
        }.getOrNull()
    }
