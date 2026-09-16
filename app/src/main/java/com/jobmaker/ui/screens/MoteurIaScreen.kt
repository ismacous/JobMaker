package com.jobmaker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jobmaker.llm.ModeMoteur
import com.jobmaker.llm.cloud.FournisseurCloud
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.EtatTest
import com.jobmaker.ui.vm.MoteurIaViewModel

/**
 * Choix du moteur : API gratuite ou modele embarque.
 *
 * L'ecran assume d'etre bavard. Confier son CV a un service distant n'est pas
 * anodin, et l'utilisateur doit pouvoir decider en connaissance de cause :
 * ce qui part, chez qui, ce que le fournisseur s'autorise a en faire, et
 * comment revenir en arriere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoteurIaScreen(
    vm: MoteurIaViewModel,
    onRetour: () -> Unit,
    onOuvrirModeles: () -> Unit,
) {
    val reglages by vm.reglages.collectAsState()
    val etat by vm.etat.collectAsState()
    val empreintes by vm.empreintes.collectAsState()
    val modelesLocaux by vm.modelesLocaux.collectAsState()
    val chaine by vm.chaine.collectAsState()
    val liens = LocalUriHandler.current

    val fournisseur = reglages.fournisseurCloud
    val empreinte = empreintes[fournisseur]
    var choixModeleOuvert by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moteur d'IA") },
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
            // Mode
            // -----------------------------------------------------------------
            SectionCarte("Ou tourne l'IA") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeMoteur.entries.forEach { mode ->
                        FilterChip(
                            selected = reglages.modeMoteur == mode,
                            onClick = { vm.setMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    reglages.modeMoteur.resume,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Bandeau(
                    "Mesure sur un telephone haut de gamme : une candidature complete " +
                        "demande une dizaine de minutes au modele embarque, et quelques " +
                        "secondes a une API. La difference ne tient pas au reseau mais a " +
                        "la taille du modele : un 120B servi par un fournisseur n'a pas " +
                        "d'equivalent tenant dans la memoire d'un telephone.",
                    TypeBandeau.INFO,
                )
            }

            if (reglages.modeMoteur == ModeMoteur.CLOUD) {

                // -------------------------------------------------------------
                // Fournisseur
                // -------------------------------------------------------------
                SectionCarte("Fournisseur", sousTitre = "Tous ont une offre gratuite sans carte bancaire") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FournisseurCloud.entries.forEach { f ->
                            FilterChip(
                                selected = fournisseur == f,
                                onClick = { vm.setFournisseur(f) },
                                label = { Text(f.nom, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    Text(fournisseur.resume, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Quota gratuit : ${fournisseur.quota}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Bandeau(
                        fournisseur.politiqueDonnees,
                        if (fournisseur == FournisseurCloud.GROQ) TypeBandeau.INFO
                        else TypeBandeau.ALERTE,
                    )
                }

                // -------------------------------------------------------------
                // Cle
                // -------------------------------------------------------------
                SectionCarte("Cle d'API ${fournisseur.nom}") {
                    if (empreinte != null) {
                        Bandeau(
                            "Cle enregistree : $empreinte\n" +
                                "Elle est chiffree par le materiel du telephone et n'est " +
                                "jamais affichee en entier, ni envoyee ailleurs que chez " +
                                "${fournisseur.nom}.",
                            TypeBandeau.SUCCES,
                        )
                    } else {
                        Text(
                            "Creez un compte gratuit, copiez la cle, collez-la ici. " +
                                "Elle reste sur ce telephone : elle n'est pas dans " +
                                "l'application distribuee, et il n'y a aucun compte JobMaker.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    OutlinedButton(
                        onClick = { liens.openUri(fournisseur.urlCle) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                        Text("  Obtenir une cle gratuite chez ${fournisseur.nom}")
                    }

                    OutlinedTextField(
                        value = etat.cleSaisie,
                        onValueChange = vm::majCleSaisie,
                        label = { Text(if (empreinte != null) "Remplacer la cle" else "Coller la cle") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                        shape = MaterialTheme.shapes.small,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = vm::enregistrerCle,
                            enabled = etat.cleSaisie.isNotBlank(),
                        ) { Text("Enregistrer") }
                        if (empreinte != null) {
                            TextButton(onClick = vm::effacerCle) { Text("Supprimer la cle") }
                        }
                    }

                    etat.message?.let { Bandeau(it, TypeBandeau.SUCCES) }
                    etat.erreur?.let { Bandeau(it, TypeBandeau.ERREUR) }
                }

                // -------------------------------------------------------------
                // Modele
                // -------------------------------------------------------------
                SectionCarte("Modele") {
                    Text(
                        reglages.modeleCloudPour(fournisseur),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Les fournisseurs retirent et renomment leurs modeles plusieurs fois " +
                            "par an. Si une generation echoue avec \"modele inexistant\", " +
                            "rafraichissez la liste et choisissez-en un autre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.rafraichirModeles()
                                choixModeleOuvert = true
                            },
                            enabled = !etat.chargementModeles,
                        ) {
                            if (etat.chargementModeles) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("  Chargement")
                            } else {
                                Text("Changer de modele")
                            }
                        }
                    }
                }

                // -------------------------------------------------------------
                // Verification
                // -------------------------------------------------------------
                SectionCarte("Verification") {
                    Button(
                        onClick = vm::tester,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = etat.test != EtatTest.EnCours,
                    ) { Text("Tester la connexion") }

                    when (val t = etat.test) {
                        is EtatTest.Repos -> Text(
                            "Un aller-retour reel avec le fournisseur : il verifie la cle, " +
                                "le modele et le reseau d'un seul coup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is EtatTest.EnCours -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("  Appel en cours...")
                        }
                        is EtatTest.Reussi -> Bandeau(
                            "Reponse de ${t.modele} en ${t.dureeMs} ms. Tout est pret.",
                            TypeBandeau.SUCCES,
                        )
                        is EtatTest.Echoue -> Bandeau(t.message, TypeBandeau.ERREUR)
                    }
                }

                // -------------------------------------------------------------
                // Chaine de secours
                // -------------------------------------------------------------
                SectionCarte(
                    "Filet de securite",
                    sousTitre = "Ce qui prend le relais quand un fournisseur sature",
                ) {
                    if (chaine.size > 1) {
                        Bandeau(
                            "Ordre d'essai :\n" +
                                chaine.mapIndexed { i, nom -> "${i + 1}. $nom" }
                                    .joinToString("\n"),
                            TypeBandeau.SUCCES,
                        )
                    } else {
                        Bandeau(
                            "Un seul moteur disponible : si son quota est atteint, la " +
                                "generation s'arrete. Enregistrez une cle chez un autre " +
                                "fournisseur, ou gardez un modele installe sur le telephone.",
                            TypeBandeau.ALERTE,
                        )
                    }

                    LigneBascule(
                        titre = "Enchainer les fournisseurs",
                        detail = "Quand le quota du fournisseur en cours est epuise, " +
                            "l'application passe au suivant dont vous avez enregistre une " +
                            "cle, au lieu de rendre la main au telephone pendant qu'une " +
                            "autre cle dort. La qualite ne baisse pas.",
                        valeur = reglages.enchainerFournisseurs,
                        onChange = vm::setEnchainer,
                    )

                    LigneBascule(
                        titre = "Repli sur le modele du telephone",
                        detail = "Dernier maillon : si plus aucun fournisseur ne repond, " +
                            "l'application termine la candidature avec le modele installe " +
                            "au lieu de tout perdre. " +
                            (
                                if (modelesLocaux.isEmpty()) {
                                    "Aucun modele n'est installe : ce maillon est absent."
                                } else {
                                    "Modele de secours : " +
                                        modelesLocaux.first().displayName + "."
                                }
                                ),
                        valeur = reglages.repliLocal,
                        onChange = vm::setRepliLocal,
                    )

                    if (modelesLocaux.isEmpty()) {
                        OutlinedButton(
                            onClick = onOuvrirModeles,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Telecharger un modele de secours") }
                    }
                }

            } else {
                SectionCarte("Modeles installes") {
                    if (modelesLocaux.isEmpty()) {
                        Bandeau(
                            "Aucun modele installe : en mode \"sur l'appareil\", " +
                                "l'application ne peut rien generer.",
                            TypeBandeau.ERREUR,
                        )
                    } else {
                        Text(
                            modelesLocaux.joinToString("\n") { "- ${it.displayName} (${it.sizeLabel})" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = onOuvrirModeles,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Gerer les modeles") }
                }
            }

            // -----------------------------------------------------------------
            // Ce qui part, et ce qui ne part pas
            // -----------------------------------------------------------------
            SectionCarte("Ce qui sort du telephone") {
                Text(
                    if (reglages.modeMoteur == ModeMoteur.CLOUD) {
                        "En mode API, chaque generation envoie a ${fournisseur.nom} : le texte " +
                            "de l'offre, et le resume de votre profil (experiences, formations, " +
                            "competences, langues). Votre nom y figure.\n\n" +
                            "Ne sortent jamais : votre photo, vos coordonnees exactes " +
                            "(telephone, adresse, e-mail) -- elles sont ajoutees au CV apres " +
                            "coup, au moment du rendu -- ainsi que vos candidatures " +
                            "enregistrees et vos PDF.\n\n" +
                            "Rien n'est envoye a qui que ce soit d'autre : pas de serveur " +
                            "JobMaker, pas de statistiques, pas de compte."
                    } else {
                        "En mode \"sur l'appareil\", rien ne sort. La seule connexion reseau " +
                            "de l'application est le telechargement des modeles, et elle " +
                            "n'envoie alors aucune de vos donnees."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    if (choixModeleOuvert) {
        DialogueChoixModele(
            modeles = etat.modeles,
            courant = reglages.modeleCloudPour(fournisseur),
            chargement = etat.chargementModeles,
            erreur = etat.erreur,
            onChoisir = {
                vm.setModele(it)
                choixModeleOuvert = false
            },
            onFermer = { choixModeleOuvert = false },
        )
    }
}

@Composable
private fun LigneBascule(
    titre: String,
    detail: String,
    valeur: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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

/**
 * Liste les modeles renvoyes par le fournisseur, et laisse toujours la
 * possibilite d'en saisir un a la main : un modele tout juste sorti apparait
 * dans la documentation avant d'apparaitre dans l'API de liste.
 */
@Composable
private fun DialogueChoixModele(
    modeles: List<String>,
    courant: String,
    chargement: Boolean,
    erreur: String?,
    onChoisir: (String) -> Unit,
    onFermer: () -> Unit,
) {
    var saisieLibre by remember { mutableStateOf(courant) }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text("Choisir un modele") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    chargement -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Interrogation du fournisseur...")
                    }
                    erreur != null -> Bandeau(erreur, TypeBandeau.ERREUR)
                    modeles.isEmpty() -> Text(
                        "Aucune liste recuperee. Saisissez l'identifiant du modele " +
                            "a la main ci-dessous.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                modeles.forEach { modele ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = modele == saisieLibre,
                            onClick = { saisieLibre = modele },
                        )
                        Text(
                            modele,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                OutlinedTextField(
                    value = saisieLibre,
                    onValueChange = { saisieLibre = it },
                    label = { Text("Identifiant du modele") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChoisir(saisieLibre.trim()) },
                enabled = saisieLibre.isNotBlank(),
            ) { Text("Utiliser") }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text("Annuler") } },
    )
}
