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
import com.jobmaker.llm.ModeMoteur
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
    onOuvrirMoteur: () -> Unit,
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
            // Moteur : le reglage qui decide de tout le reste
            // -----------------------------------------------------------------
            SectionCarte(
                "Moteur d'IA",
                sousTitre = "Ou tourne le calcul, et avec quel modele",
            ) {
                val cloud = reglages.modeMoteur == ModeMoteur.CLOUD
                Text(
                    if (cloud) {
                        "API gratuite : ${reglages.fournisseurCloud.nom} " +
                            "(${reglages.modeleCloudPour(reglages.fournisseurCloud)}). " +
                            "Comptez quelques secondes par candidature."
                    } else {
                        "Sur l'appareil. Rien ne sort du telephone, comptez plusieurs " +
                            "minutes par candidature."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onOuvrirMoteur,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Changer de moteur, de cle ou de modele") }
            }

            // -----------------------------------------------------------------
            // Affectation des modeles aux etapes
            // -----------------------------------------------------------------
            SectionCarte(
                "Modeles par etape",
                sousTitre = "S'applique au moteur embarque uniquement",
                replierParDefaut = reglages.modeMoteur == ModeMoteur.CLOUD,
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
                if (reglages.modeMoteur == ModeMoteur.CLOUD) {
                    LigneInterrupteur(
                        titre = "Relecture automatique",
                        detail = "Deux etapes supplementaires relisent le CV et la lettre, " +
                            "puis les reecrivent. C'est ce qui separe un CV correct d'un " +
                            "CV bon, et le calcul ne coute que quelques secondes.\n\n" +
                            "A desactiver si le quota gratuit vous fait attendre : les " +
                            "quotas se comptent en tokens par minute, et ces deux etapes " +
                            "peuvent imposer une attente chacune. Les controles " +
                            "automatiques (employeur, diplome ou chiffre absent du profil) " +
                            "tournent de toute facon, et le bouton \"Relire\" sur une " +
                            "candidature terminee refait le travail quand vous le decidez.",
                        valeur = reglages.relectureCloud,
                        onChange = vm::setRelectureCloud,
                    )
                } else {
                    LigneInterrupteur(
                        titre = "Relecture automatique",
                        detail = "Deux etapes supplementaires relisent le CV et la lettre, " +
                            "puis les reecrivent. Sur le moteur embarque elles doublent le " +
                            "temps de generation, d'ou leur desactivation par defaut : les " +
                            "controles automatiques (employeur, diplome ou chiffre absent " +
                            "du profil) tournent de toute facon, et le bouton \"Relire\" " +
                            "sur une candidature terminee fait la meme chose quand vous " +
                            "le decidez.",
                        valeur = reglages.relectureActive,
                        onChange = vm::setRelecture,
                    )
                }

                val relectureActive =
                    if (reglages.modeMoteur == ModeMoteur.CLOUD) reglages.relectureCloud
                    else reglages.relectureActive
                if (relectureActive) {
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

                LigneInterrupteur(
                    titre = "Charger le modele entierement en memoire",
                    detail = "Par defaut, les poids du modele restent des pages du fichier, " +
                        "lues a la demande : l'ouverture est immediate. Mais si Android " +
                        "manque de memoire, il evince ces pages et doit les relire depuis " +
                        "le stockage -- sur 2,5 Go relus a chaque mot produit, la vitesse " +
                        "s'effondre.\n\n" +
                        "Active, tout est copie en memoire une fois pour toutes : " +
                        "l'ouverture prend une dizaine de secondes, puis le debit reste " +
                        "constant. A essayer si la generation est anormalement lente. " +
                        "Si l'application se ferme d'elle-meme, desactivez.",
                    valeur = reglages.chargerEnMemoire,
                    onChange = vm::setChargerEnMemoire,
                )
            }

            SectionCarte("Confidentialite", replierParDefaut = true) {
                Text(
                    if (reglages.modeMoteur == ModeMoteur.CLOUD) {
                        "Moteur actuel : ${reglages.fournisseurCloud.nom}. A chaque " +
                            "generation, le texte de l'offre et le resume de votre profil " +
                            "sont envoyes chez ce fournisseur. Vos coordonnees exactes, " +
                            "votre photo, vos candidatures enregistrees et vos PDF ne " +
                            "partent jamais : ils sont ajoutes au moment du rendu, sur " +
                            "l'appareil.\n\n" +
                            "Pour que plus rien ne sorte du telephone, basculez le moteur " +
                            "sur \"Sur l'appareil\"."
                    } else {
                        "Votre profil, vos offres, vos CV et vos lettres ne quittent jamais " +
                            "ce telephone. La seule connexion reseau de l'application est le " +
                            "telechargement des modeles depuis HuggingFace, et elle n'envoie " +
                            "alors aucune de vos donnees."
                    } + "\n\n" +
                        "Dans les deux cas : pensez a exporter votre profil (onglet Profil, " +
                        "en bas). En cas de perte du telephone, c'est votre seule sauvegarde.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onOuvrirMoteur,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Voir le detail de ce qui est envoye") }
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
