package com.jobmaker.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jobmaker.di.AppContainer
import com.jobmaker.ui.screens.BienvenueScreen
import com.jobmaker.ui.screens.CandidatureScreen
import com.jobmaker.ui.screens.ComprendreScreen
import com.jobmaker.ui.screens.DocumentsScreen
import com.jobmaker.ui.screens.EditeurCvScreen
import com.jobmaker.ui.screens.EditeurLettreScreen
import com.jobmaker.ui.screens.GenererScreen
import com.jobmaker.ui.screens.ModelesScreen
import com.jobmaker.ui.screens.MoteurIaScreen
import com.jobmaker.ui.screens.ProfilScreen
import com.jobmaker.ui.screens.ReglagesScreen
import com.jobmaker.ui.theme.JobMakerTheme
import com.jobmaker.ui.vm.DocumentsViewModel
import com.jobmaker.ui.vm.ExplainViewModel
import com.jobmaker.ui.vm.GenerateViewModel
import com.jobmaker.ui.vm.ModelsViewModel
import com.jobmaker.ui.vm.MoteurIaViewModel
import com.jobmaker.ui.vm.ProfileViewModel
import com.jobmaker.ui.vm.SettingsViewModel
import com.jobmaker.ui.vm.vmFactory

object Routes {
    const val BIENVENUE = "bienvenue"
    const val GENERER = "generer"
    const val DOCUMENTS = "documents"
    const val PROFIL = "profil"
    const val COMPRENDRE = "comprendre"
    const val REGLAGES = "reglages"
    const val MODELES = "modeles"
    const val MOTEUR_IA = "moteur-ia"
    const val CANDIDATURE = "candidature/{id}"
    const val EDITEUR_CV = "editeur-cv/{id}"
    const val EDITEUR_LETTRE = "editeur-lettre/{id}"

    fun candidature(id: String) = "candidature/$id"
    fun editeurCv(id: String) = "editeur-cv/$id"
    fun editeurLettre(id: String) = "editeur-lettre/$id"
}

private data class Onglet(val route: String, val libelle: String, val icone: ImageVector)

private val onglets = listOf(
    Onglet(Routes.GENERER, "Candidater", Icons.Default.AutoAwesome),
    Onglet(Routes.DOCUMENTS, "Documents", Icons.Default.Description),
    Onglet(Routes.COMPRENDRE, "Comprendre", Icons.Default.HelpOutline),
    Onglet(Routes.PROFIL, "Profil", Icons.Default.Person),
)

@Composable
fun JobMakerApplication(
    container: AppContainer,
    offrePartagee: String?,
    onOffrePartageeConsommee: () -> Unit,
) {
    JobMakerTheme {
        val navController = rememberNavController()
        val factory = vmFactory(container)

        // Les vues-modeles sont crees au niveau du graphe pour que l'etat d'une
        // generation en cours survive a un changement d'onglet : une generation
        // dure plusieurs minutes, l'utilisateur va forcement naviguer entre-temps.
        val generateVm: GenerateViewModel = viewModel(factory = factory)
        val documentsVm: DocumentsViewModel = viewModel(factory = factory)
        val profileVm: ProfileViewModel = viewModel(factory = factory)
        val explainVm: ExplainViewModel = viewModel(factory = factory)
        val modelsVm: ModelsViewModel = viewModel(factory = factory)
        val settingsVm: SettingsViewModel = viewModel(factory = factory)
        val moteurVm: MoteurIaViewModel = viewModel(factory = factory)

        val reglages by settingsVm.reglages.collectAsState()

        LaunchedEffect(offrePartagee) {
            val texte = offrePartagee ?: return@LaunchedEffect
            generateVm.majOffre(texte)
            navController.navigate(Routes.GENERER) { launchSingleTop = true }
            onOffrePartageeConsommee()
        }

        val entree by navController.currentBackStackEntryAsState()
        val routeCourante = entree?.destination?.route
        val afficherBarre = routeCourante in onglets.map { it.route }

        Scaffold(
            // Chaque ecran a son propre Scaffold avec sa barre de titre : sans
            // cela, l'encoche haute serait compensee deux fois.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (afficherBarre) {
                    NavigationBar {
                        onglets.forEach { onglet ->
                            val selectionne = entree?.destination?.hierarchy
                                ?.any { it.route == onglet.route } == true
                            NavigationBarItem(
                                selected = selectionne,
                                onClick = {
                                    navController.navigate(onglet.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(onglet.icone, contentDescription = null) },
                                label = { Text(onglet.libelle) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = if (reglages.onboardingFait) Routes.GENERER else Routes.BIENVENUE,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.BIENVENUE) {
                    BienvenueScreen(
                        modelsVm = modelsVm,
                        onTermine = {
                            settingsVm.setOnboardingFait(true)
                            navController.navigate(Routes.PROFIL) {
                                popUpTo(Routes.BIENVENUE) { inclusive = true }
                            }
                        },
                        onOuvrirModeles = { navController.navigate(Routes.MODELES) },
                        onOuvrirMoteur = { navController.navigate(Routes.MOTEUR_IA) },
                    )
                }

                composable(Routes.GENERER) {
                    GenererScreen(
                        vm = generateVm,
                        onVoirCandidature = { navController.navigate(Routes.candidature(it)) },
                        onOuvrirProfil = { navController.navigate(Routes.PROFIL) },
                        onOuvrirModeles = { navController.navigate(Routes.MODELES) },
                        onOuvrirReglages = { navController.navigate(Routes.REGLAGES) },
                        onOuvrirMoteur = { navController.navigate(Routes.MOTEUR_IA) },
                    )
                }

                composable(Routes.DOCUMENTS) {
                    DocumentsScreen(
                        vm = documentsVm,
                        onOuvrir = { navController.navigate(Routes.candidature(it)) },
                        onNouvelle = { navController.navigate(Routes.GENERER) },
                    )
                }

                composable(Routes.COMPRENDRE) {
                    ComprendreScreen(
                        vm = explainVm,
                        onOuvrirModeles = { navController.navigate(Routes.MODELES) },
                    )
                }

                composable(Routes.PROFIL) {
                    ProfilScreen(
                        vm = profileVm,
                        onOuvrirReglages = { navController.navigate(Routes.REGLAGES) },
                    )
                }

                composable(Routes.REGLAGES) {
                    ReglagesScreen(
                        vm = settingsVm,
                        onRetour = { navController.popBackStack() },
                        onOuvrirModeles = { navController.navigate(Routes.MODELES) },
                        onOuvrirMoteur = { navController.navigate(Routes.MOTEUR_IA) },
                    )
                }

                composable(Routes.MODELES) {
                    ModelesScreen(vm = modelsVm, onRetour = { navController.popBackStack() })
                }

                composable(Routes.MOTEUR_IA) {
                    MoteurIaScreen(
                        vm = moteurVm,
                        onRetour = { navController.popBackStack() },
                        onOuvrirModeles = { navController.navigate(Routes.MODELES) },
                    )
                }

                composable(Routes.CANDIDATURE) { backStack ->
                    val id = backStack.arguments?.getString("id").orEmpty()
                    CandidatureScreen(
                        candidatureId = id,
                        vm = documentsVm,
                        onRetour = { navController.popBackStack() },
                        onEditerCv = { navController.navigate(Routes.editeurCv(id)) },
                        onEditerLettre = { navController.navigate(Routes.editeurLettre(id)) },
                        onRegenerer = { offre ->
                            generateVm.majOffre(offre)
                            navController.navigate(Routes.GENERER)
                        },
                    )
                }

                composable(Routes.EDITEUR_CV) { backStack ->
                    val id = backStack.arguments?.getString("id").orEmpty()
                    EditeurCvScreen(
                        candidatureId = id,
                        vm = documentsVm,
                        onRetour = { navController.popBackStack() },
                    )
                }

                composable(Routes.EDITEUR_LETTRE) { backStack ->
                    val id = backStack.arguments?.getString("id").orEmpty()
                    EditeurLettreScreen(
                        candidatureId = id,
                        vm = documentsVm,
                        onRetour = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
