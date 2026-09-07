package com.jobmaker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jobmaker.data.prefs.LangueSortie
import com.jobmaker.llm.AgentRole
import com.jobmaker.render.CvTemplates
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReglagesScreen(
    vm: SettingsViewModel,
    onRetour: () -> Unit,
    onOuvrirModeles: () -> Unit,
) {
    val reglages by vm.reglages.collectAsState()
    val installes by vm.installes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reglages") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // -----------------------------------------------------------------
            // Affectation des modeles aux etapes
            // -----------------------------------------------------------------
            SectionCarte(
                "Modeles par etape",
                sousTitre = "Vous pouvez confier chaque etape a un modele different",
            ) {
                if (installes.isEmpty()) {
                    Bandeau("Aucun modele installe.", TypeBandeau.ERREUR) {
                        OutlinedButton(onClick = onOuvrirModeles) { Text("Telecharger") }
                    }
                } else {
                    Bandeau(
                        "Un seul modele reste en memoire a la fois : un telephone ne peut pas " +
                            "faire tourner deux modeles quantifies en parallele. Si vous " +
                            "affectez des modeles differents, l'application les charge et les " +
                            "decharge entre les etapes, ce qui ajoute quelques secondes a " +
                            "chaque changement. Le reglage le plus rapide est donc le meme " +
                            "modele partout ; le plus fin est un petit modele pour l'analyse " +
                            "et un gros pour la redaction.",
                        TypeBandeau.INFO,
                    )
                    AgentRole.entries.forEach { role ->
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(role.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                role.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                installes.forEach { modele ->
                                    FilterChip(
                                        selected = reglages.modelePour(role) == modele.id,
                                        onClick = { vm.setModelePourRole(role, modele.id) },
                                        label = {
                                            Text(
                                                modele.displayName,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { installes.firstOrNull()?.let { vm.setModelePourTous(it.id) } },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("Utiliser le premier modele partout") }
                }
                OutlinedButton(
                    onClick = onOuvrirModeles,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { Text("Gerer les modeles") }
            }

            // -----------------------------------------------------------------
            // Qualite de generation
            // -----------------------------------------------------------------
            SectionCarte("Qualite de la generation") {
                LigneInterrupteur(
                    titre = "Relecture automatique",
                    detail = "Une etape supplementaire cherche les inventions, les oublis de " +
                        "mots-cles et les maladresses, puis corrige. Double le temps de " +
                        "generation, mais c'est ce qui empeche un CV de contenir un employeur " +
                        "ou un chiffre que vous n'avez jamais donne. A laisser active.",
                    valeur = reglages.relectureActive,
                    onChange = vm::setRelecture,
                )

                if (reglages.relectureActive) {
                    Text(
                        "Passes de correction : ${reglages.passesCorrection}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Slider(
                        value = reglages.passesCorrection.toFloat(),
                        onValueChange = { vm.setPasses(it.toInt()) },
                        valueRange = 0f..2f,
                        steps = 1,
                    )
                    Text(
                        "0 : la relecture signale sans corriger. 1 : recommande. " +
                            "2 : plus propre, mais long.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LigneInterrupteur(
                    titre = "CV sur une page",
                    detail = "Au-dela d'une page, un CV n'est plus lu en entier. " +
                        "A desactiver seulement si vous avez plus de quinze ans de carriere.",
                    valeur = reglages.cvUnePage,
                    onChange = vm::setUnePage,
                )

                Text(
                    "Langue des documents",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LangueSortie.entries.forEach { langue ->
                        FilterChip(
                            selected = reglages.langueSortie == langue,
                            onClick = { vm.setLangue(langue) },
                            label = { Text(langue.label) },
                        )
                    }
                }
                Text(
                    "\"Comme l'annonce\" est le bon choix : une annonce en anglais attend un " +
                        "CV en anglais.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -----------------------------------------------------------------
            // Apparence des documents
            // -----------------------------------------------------------------
            SectionCarte("Apparence par defaut des documents") {
                Text("Gabarit", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CvTemplates.tous.forEach { gabarit ->
                        FilterChip(
                            selected = reglages.gabaritParDefaut == gabarit.id,
                            onClick = { vm.setGabarit(gabarit.id) },
                            label = { Text(gabarit.nom) },
                        )
                    }
                }
                Text(
                    CvTemplates.byId(reglages.gabaritParDefaut).description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text(
                    "Couleur d'accent",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CvTemplates.couleurs.forEach { (hex, nom) ->
                        FilterChip(
                            selected = reglages.couleurAccent == hex,
                            onClick = { vm.setCouleur(hex) },
                            label = { Text(nom, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }

                LigneInterrupteur(
                    titre = "Afficher la photo sur le CV",
                    detail = "En France, la photo n'est pas attendue et peut jouer contre vous " +
                        "dans les grandes entreprises. Elle reste utile dans le commerce, " +
                        "l'hotellerie et les metiers de contact.",
                    valeur = reglages.photoSurCv,
                    onChange = vm::setPhoto,
                )
            }

            // -----------------------------------------------------------------
            // Performance
            // -----------------------------------------------------------------
            SectionCarte(
                "Performance",
                sousTitre = "A ne toucher qu'en cas de lenteur ou de fermeture inopinee",
                replierParDefaut = true,
            ) {
                Text(
                    "Fenetre de contexte : ${reglages.tailleContexte} tokens",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = reglages.tailleContexte.toFloat(),
                    onValueChange = { vm.setContexte((it / 1024).toInt() * 1024) },
                    valueRange = 2048f..32768f,
                    steps = 14,
                )
                Text(
                    "Quantite de texte que le modele peut lire d'un coup (offre + profil + " +
                        "sortie). 8192 convient a la plupart des annonces. Augmenter consomme " +
                        "beaucoup plus de memoire ; si l'application se ferme pendant une " +
                        "generation, reduisez d'abord cette valeur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "Threads processeur : ${reglages.threads} (sur ${vm.nombreCoeurs} coeurs)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = reglages.threads.toFloat(),
                    onValueChange = { vm.setThreads(it.toInt()) },
                    valueRange = 1f..vm.nombreCoeurs.toFloat(),
                    steps = (vm.nombreCoeurs - 2).coerceAtLeast(0),
                )
                Text(
                    "Utiliser tous les coeurs n'accelere pas forcement : la memoire devient le " +
                        "facteur limitant, et le telephone chauffe puis se bride. " +
                        "Laisser un ou deux coeurs libres donne souvent le meilleur resultat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "Couches deportees sur le GPU : ${reglages.couchesGpu}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = reglages.couchesGpu.toFloat(),
                    onValueChange = { vm.setCouchesGpu(it.toInt()) },
                    valueRange = 0f..40f,
                    steps = 7,
                )
                Text(
                    "Sans effet si l'application a ete compilee sans le backend OpenCL " +
                        "(jobmaker.opencl=false, valeur par defaut). Laissez 0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCarte("Confidentialite", replierParDefaut = true) {
                Text(
                    "Votre profil, vos offres, vos CV et vos lettres ne quittent jamais ce " +
                        "telephone. La seule connexion reseau de l'application est le " +
                        "telechargement des modeles depuis HuggingFace, et elle n'envoie " +
                        "alors aucune de vos donnees.\n\n" +
                        "Pensez a exporter votre profil (onglet Profil, en bas) : " +
                        "en cas de perte du telephone, c'est votre seule sauvegarde.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun LigneInterrupteur(
    titre: String,
    detail: String,
    valeur: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(titre, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = valeur, onCheckedChange = onChange)
    }
}
