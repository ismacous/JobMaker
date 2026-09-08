package com.jobmaker.ui.screens

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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jobmaker.data.model.CvExperience
import com.jobmaker.data.model.CvFormation
import com.jobmaker.data.model.CvGroupeCompetences
import com.jobmaker.data.model.CvLangue
import com.jobmaker.data.model.CvProjet
import com.jobmaker.ui.components.Bandeau
import com.jobmaker.ui.components.Champ
import com.jobmaker.ui.components.ListeChaines
import com.jobmaker.ui.components.SectionCarte
import com.jobmaker.ui.components.remplacer
import com.jobmaker.ui.components.TypeBandeau
import com.jobmaker.ui.vm.DocumentsViewModel

/**
 * Retouche manuelle du CV genere.
 *
 * L'IA propose, l'utilisateur tranche. Chaque modification est enregistree
 * immediatement et le score de couverture des mots-cles se recalcule, ce qui
 * permet de voir tout de suite si une reformulation a fait perdre un terme
 * important.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditeurCvScreen(
    candidatureId: String,
    vm: DocumentsViewModel,
    onRetour: () -> Unit,
) {
    LaunchedEffect(candidatureId) { vm.charger(candidatureId) }
    val candidature by vm.courante.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier le CV") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    candidature?.let {
                        Text(
                            "Mots-cles ${it.scoreAts} %",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val c = candidature ?: return@Scaffold
        val cv = c.cv

        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Bandeau(
                "Les modifications sont enregistrees au fur et a mesure. " +
                    "Vos nom, telephone et adresse viennent de l'onglet Profil : " +
                    "ils ne s'editent pas ici.",
                TypeBandeau.INFO,
            )

            SectionCarte("En-tete") {
                Champ(cv.titre, { v -> vm.majCv { it.copy(titre = v) } }, "Titre du CV",
                    aide = "Reprend l'intitule de l'offre. C'est le premier filtre.")
                Champ(cv.accroche, { v -> vm.majCv { it.copy(accroche = v) } },
                    "Accroche", lignes = 4,
                    aide = "2 a 4 lignes. Pas de \"je\".")
            }

            // --- experiences ---
            SectionCarte(
                "Experiences",
                sousTitre = "${cv.experiences.size} bloc(s)",
                actionTitre = "Ajouter une experience",
                onAction = { vm.majCv { it.copy(experiences = it.experiences + CvExperience()) } },
            ) {
                cv.experiences.forEachIndexed { index, experience ->
                    BlocExperienceCv(
                        experience = experience,
                        premier = index == 0,
                        dernier = index == cv.experiences.lastIndex,
                        onChange = { nouvelle ->
                            vm.majCv { contenu ->
                                contenu.copy(
                                    experiences = contenu.experiences.toMutableList()
                                        .also { it[index] = nouvelle }
                                )
                            }
                        },
                        onSupprimer = {
                            vm.majCv { contenu ->
                                contenu.copy(
                                    experiences = contenu.experiences
                                        .filterIndexed { i, _ -> i != index }
                                )
                            }
                        },
                        onDeplacer = { delta ->
                            vm.majCv { contenu ->
                                contenu.copy(experiences = deplacer(contenu.experiences, index, delta))
                            }
                        },
                    )
                    if (index != cv.experiences.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            // --- formations ---
            SectionCarte(
                "Formation",
                actionTitre = "Ajouter une formation",
                onAction = { vm.majCv { it.copy(formations = it.formations + CvFormation()) } },
            ) {
                cv.formations.forEachIndexed { index, formation ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Champ(formation.diplome, { v ->
                            majFormation(vm, index) { it.copy(diplome = v) }
                        }, "Diplome")
                        Champ(formation.etablissement, { v ->
                            majFormation(vm, index) { it.copy(etablissement = v) }
                        }, "Etablissement")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Champ(formation.lieu, { v ->
                                majFormation(vm, index) { it.copy(lieu = v) }
                            }, "Lieu", Modifier.weight(1f))
                            Champ(formation.periode, { v ->
                                majFormation(vm, index) { it.copy(periode = v) }
                            }, "Periode", Modifier.weight(1f))
                        }
                        Champ(formation.detail, { v ->
                            majFormation(vm, index) { it.copy(detail = v) }
                        }, "Detail (mention, specialite)")
                        TextButton(onClick = {
                            vm.majCv { c2 ->
                                c2.copy(formations = c2.formations.filterIndexed { i, _ -> i != index })
                            }
                        }) { Text("Supprimer cette formation") }
                    }
                }
            }

            // --- competences ---
            SectionCarte(
                "Competences",
                actionTitre = "Ajouter un groupe",
                onAction = {
                    vm.majCv { it.copy(competences = it.competences + CvGroupeCompetences()) }
                },
            ) {
                cv.competences.forEachIndexed { index, groupe ->
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Champ(groupe.categorie, { v ->
                                vm.majCv { c2 ->
                                    c2.copy(
                                        competences = c2.competences.toMutableList()
                                            .also { it[index] = groupe.copy(categorie = v) }
                                    )
                                }
                            }, "Categorie", Modifier.weight(1f))
                            IconButton(onClick = {
                                vm.majCv { c2 ->
                                    c2.copy(
                                        competences = c2.competences.filterIndexed { i, _ -> i != index }
                                    )
                                }
                            }) { Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp)) }
                        }
                        ListeChaines(
                            titre = "Competences du groupe",
                            valeurs = groupe.items,
                            onAjouter = { valeur ->
                                vm.majCv { c2 ->
                                    c2.copy(
                                        competences = c2.competences.toMutableList().also {
                                            it[index] = groupe.copy(items = groupe.items + valeur)
                                        }
                                    )
                                }
                            },
                            onSupprimer = { i ->
                                vm.majCv { c2 ->
                                    c2.copy(
                                        competences = c2.competences.toMutableList().also {
                                            it[index] = groupe.copy(
                                                items = groupe.items.filterIndexed { j, _ -> j != i }
                                            )
                                        }
                                    )
                                }
                            },
                            onModifier = { i, v ->
                                vm.majCv { c2 ->
                                    c2.copy(
                                        competences = c2.competences.toMutableList().also {
                                            it[index] = groupe.copy(items = groupe.items.remplacer(i, v))
                                        }
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // --- langues ---
            SectionCarte(
                "Langues",
                actionTitre = "Ajouter une langue",
                onAction = { vm.majCv { it.copy(langues = it.langues + CvLangue()) } },
            ) {
                cv.langues.forEachIndexed { index, langue ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Champ(langue.nom, { v ->
                            vm.majCv { c2 ->
                                c2.copy(langues = c2.langues.toMutableList()
                                    .also { it[index] = langue.copy(nom = v) })
                            }
                        }, "Langue", Modifier.weight(1f))
                        Champ(langue.niveau, { v ->
                            vm.majCv { c2 ->
                                c2.copy(langues = c2.langues.toMutableList()
                                    .also { it[index] = langue.copy(niveau = v) })
                            }
                        }, "Niveau", Modifier.weight(1f))
                        IconButton(onClick = {
                            vm.majCv { c2 ->
                                c2.copy(langues = c2.langues.filterIndexed { i, _ -> i != index })
                            }
                        }) { Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp)) }
                    }
                }
            }

            SectionCarte("Certifications", replierParDefaut = true) {
                ListeChaines(
                    titre = "Certifications",
                    valeurs = cv.certifications,
                    onAjouter = { v -> vm.majCv { it.copy(certifications = it.certifications + v) } },
                    onSupprimer = { i ->
                        vm.majCv {
                            it.copy(certifications = it.certifications.filterIndexed { j, _ -> j != i })
                        }
                    },
                    onModifier = { i, v ->
                        vm.majCv { it.copy(certifications = it.certifications.remplacer(i, v)) }
                    },
                )
            }

            SectionCarte("Projets", replierParDefaut = true,
                actionTitre = "Ajouter un projet",
                onAction = { vm.majCv { it.copy(projets = it.projets + CvProjet()) } },
            ) {
                cv.projets.forEachIndexed { index, projet ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Champ(projet.nom, { v ->
                            vm.majCv { c2 ->
                                c2.copy(projets = c2.projets.toMutableList()
                                    .also { it[index] = projet.copy(nom = v) })
                            }
                        }, "Nom du projet")
                        Champ(projet.description, { v ->
                            vm.majCv { c2 ->
                                c2.copy(projets = c2.projets.toMutableList()
                                    .also { it[index] = projet.copy(description = v) })
                            }
                        }, "Description", lignes = 2)
                        TextButton(onClick = {
                            vm.majCv { c2 ->
                                c2.copy(projets = c2.projets.filterIndexed { i, _ -> i != index })
                            }
                        }) { Text("Supprimer") }
                    }
                }
            }

            SectionCarte("Bas de page", replierParDefaut = true) {
                ListeChaines(
                    titre = "Informations complementaires",
                    aide = "Permis, vehicule, disponibilite : seulement si c'est un atout ici.",
                    valeurs = cv.infosComplementaires,
                    onAjouter = { v ->
                        vm.majCv { it.copy(infosComplementaires = it.infosComplementaires + v) }
                    },
                    onSupprimer = { i ->
                        vm.majCv {
                            it.copy(infosComplementaires =
                                it.infosComplementaires.filterIndexed { j, _ -> j != i })
                        }
                    },
                    onModifier = { i, v ->
                        vm.majCv { it.copy(infosComplementaires = it.infosComplementaires.remplacer(i, v)) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                ListeChaines(
                    titre = "Centres d'interet",
                    valeurs = cv.centresInteret,
                    onAjouter = { v -> vm.majCv { it.copy(centresInteret = it.centresInteret + v) } },
                    onSupprimer = { i ->
                        vm.majCv {
                            it.copy(centresInteret = it.centresInteret.filterIndexed { j, _ -> j != i })
                        }
                    },
                    onModifier = { i, v ->
                        vm.majCv { it.copy(centresInteret = it.centresInteret.remplacer(i, v)) }
                    },
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun BlocExperienceCv(
    experience: CvExperience,
    premier: Boolean,
    dernier: Boolean,
    onChange: (CvExperience) -> Unit,
    onSupprimer: () -> Unit,
    onDeplacer: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                experience.poste.ifBlank { "Nouvelle experience" },
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
        Champ(experience.poste, { onChange(experience.copy(poste = it)) }, "Poste")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Champ(experience.entreprise, { onChange(experience.copy(entreprise = it)) },
                "Entreprise", Modifier.weight(1f))
            Champ(experience.lieu, { onChange(experience.copy(lieu = it)) },
                "Lieu", Modifier.weight(1f))
        }
        Champ(experience.periode, { onChange(experience.copy(periode = it)) },
            "Periode", aide = "Exemple : mars 2022 - juin 2024")
        Spacer(Modifier.height(4.dp))
        ListeChaines(
            titre = "Puces",
            aide = "Une action par puce, commencant par un verbe. Deux lignes maximum.",
            valeurs = experience.puces,
            multiligne = true,
            onAjouter = { onChange(experience.copy(puces = experience.puces + it)) },
            onSupprimer = { i ->
                onChange(experience.copy(puces = experience.puces.filterIndexed { j, _ -> j != i }))
            },
            onModifier = { i, v -> onChange(experience.copy(puces = experience.puces.remplacer(i, v))) },
        )
    }
}

private fun majFormation(
    vm: DocumentsViewModel,
    index: Int,
    bloc: (CvFormation) -> CvFormation,
) = vm.majCv { cv ->
    cv.copy(
        formations = cv.formations.toMutableList().also { it[index] = bloc(it[index]) }
    )
}

private fun <T> deplacer(liste: List<T>, index: Int, delta: Int): List<T> {
    val cible = (index + delta).coerceIn(0, liste.size - 1)
    if (cible == index) return liste
    val copie = liste.toMutableList()
    copie.add(cible, copie.removeAt(index))
    return copie
}
