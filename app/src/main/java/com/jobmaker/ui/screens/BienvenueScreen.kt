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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobmaker.llm.DownloadState
import com.jobmaker.llm.ModelTier
import com.jobmaker.llm.formatBytes
import com.jobmaker.ui.vm.ModelsViewModel

/**
 * Premier lancement.
 *
 * Trois choses a faire comprendre avant tout : rien ne sort du telephone, il
 * faut telecharger un modele une fois, et la qualite du resultat depend
 * directement du soin mis a remplir le profil.
 */
@Composable
fun BienvenueScreen(
    modelsVm: ModelsViewModel,
    onTermine: () -> Unit,
    onOuvrirModeles: () -> Unit,
) {
    val installes by modelsVm.installes.collectAsState()
    val telechargements by modelsVm.telechargements.collectAsState()

    val recommande = modelsVm.catalogue.firstOrNull {
        it.tier == ModelTier.RECOMMENDED && it.id.contains("q4")
    } ?: modelsVm.catalogue.first()
    val etat = telechargements[recommande.id]

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        Text("JobMaker", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Un CV et une lettre adaptes a chaque offre, generes sur votre telephone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(22.dp))

        Etape(
            numero = "1",
            titre = "Rien ne quitte votre telephone",
            texte = "L'IA tourne en local. Votre profil, les offres que vous collez, vos CV " +
                "et vos lettres restent sur l'appareil. Aucun compte, aucun abonnement, " +
                "aucun envoi vers un serveur.",
        )
        Etape(
            numero = "2",
            titre = "Un modele a telecharger, une seule fois",
            texte = "C'est le cerveau de l'application : environ 2,5 Go a recuperer en Wi-Fi. " +
                "Gardez l'application ouverte le temps du telechargement -- s'il " +
                "s'interrompt, il reprendra ou il en etait. Ensuite, tout fonctionne hors " +
                "ligne, y compris sans forfait.",
        )
        Etape(
            numero = "3",
            titre = "Remplissez votre profil en detail",
            texte = "C'est la seule etape qui demande du temps, et celle qui decide de tout : " +
                "l'IA n'a pas le droit d'inventer une experience. Elle ne peut mettre en " +
                "avant que ce que vous lui avez donne. Une heure passee sur cette page vaut " +
                "des dizaines de candidatures.",
        )
        Etape(
            numero = "4",
            titre = "Puis une offre a la fois",
            texte = "Vous collez une annonce, l'application analyse ce qu'elle demande " +
                "vraiment, choisit quoi mettre en avant dans votre parcours, redige, relit " +
                "et corrige. Vous relisez, vous exportez en PDF, vous envoyez.",
        )

        Spacer(Modifier.height(14.dp))

        // --- telechargement du modele recommande ---
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Modele recommande",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    recommande.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${recommande.approxSizeLabel} · ${recommande.languages}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(10.dp))

                when {
                    installes.any { it.id == recommande.id } || installes.isNotEmpty() -> {
                        Text(
                            "Modele installe. Vous pouvez commencer.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    etat is DownloadState.Running -> Column {
                        LinearProgressIndicator(
                            progress = { etat.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (etat.bytesTotal > 0)
                                "${formatBytes(etat.bytesDone)} / ${formatBytes(etat.bytesTotal)} " +
                                    "· ${formatBytes(etat.bytesPerSecond)}/s"
                            else formatBytes(etat.bytesDone),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            "Vous pouvez remplir votre profil pendant le telechargement.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    etat is DownloadState.Resolving -> Text(
                        etat.message,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    etat is DownloadState.Failed -> Column {
                        Text(
                            etat.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { modelsVm.telecharger(recommande) },
                            modifier = Modifier.padding(top = 6.dp),
                        ) { Text("Reessayer") }
                    }

                    else -> Button(
                        onClick = { modelsVm.telecharger(recommande) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Telecharger (${recommande.approxSizeLabel})") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onOuvrirModeles, modifier = Modifier.weight(1f)) {
                Text("Voir tous les modeles")
            }
            Button(onClick = onTermine, modifier = Modifier.weight(1f)) {
                Text("Commencer")
            }
        }
        TextButton(onClick = onTermine, modifier = Modifier.fillMaxWidth()) {
            Text("Passer cette introduction")
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Etape(numero: String, titre: String, texte: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Surface(
            Modifier.size(28.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    numero,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(titre, style = MaterialTheme.typography.titleSmall)
            Text(
                texte,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
