package com.jobmaker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jobmaker.ui.theme.CouleurAlerte
import com.jobmaker.ui.theme.CouleurSucces

@Composable
fun Champ(
    valeur: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    aide: String? = null,
    lignes: Int = 1,
    clavier: KeyboardType = KeyboardType.Text,
    obligatoire: Boolean = false,
) {
    OutlinedTextField(
        value = valeur,
        onValueChange = onChange,
        label = { Text(if (obligatoire) "$label *" else label) },
        supportingText = aide?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = lignes == 1,
        minLines = lignes,
        maxLines = if (lignes == 1) 1 else lignes + 6,
        keyboardOptions = KeyboardOptions(
            keyboardType = clavier,
            imeAction = if (lignes == 1) ImeAction.Next else ImeAction.Default,
        ),
        shape = MaterialTheme.shapes.small,
    )
}

@Composable
fun SectionCarte(
    titre: String,
    modifier: Modifier = Modifier,
    sousTitre: String? = null,
    actionTitre: String? = null,
    onAction: (() -> Unit)? = null,
    replierParDefaut: Boolean = false,
    contenu: @Composable () -> Unit,
) {
    var deplie by remember { mutableStateOf(!replierParDefaut) }
    ElevatedCard(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(titre, style = MaterialTheme.typography.titleMedium)
                    if (sousTitre != null) {
                        Text(
                            sousTitre,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { deplie = !deplie }) {
                    Icon(
                        if (deplie) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (deplie) "Replier" else "Deplier",
                    )
                }
            }
            if (deplie) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    contenu()
                }
                if (actionTitre != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                        Text("  $actionTitre")
                    }
                }
            }
        }
    }
}

/**
 * Editeur de liste de chaines : une puce par element, un champ pour en ajouter.
 * Utilise partout ou l'utilisateur saisit des missions, des competences, des
 * centres d'interet.
 */
/**
 * Editeur d'une liste de chaines : un champ modifiable par element, plus un
 * champ pour en ajouter.
 *
 * Les elements sont editables sur place. C'est indispensable : une faute de
 * frappe dans une mission longuement redigee ne doit pas obliger a tout
 * resaisir. [onModifier] peut etre omis pour les listes en lecture seule.
 */
@Composable
fun ListeChaines(
    titre: String,
    valeurs: List<String>,
    onAjouter: (String) -> Unit,
    onSupprimer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onModifier: ((Int, String) -> Unit)? = null,
    aide: String? = null,
    multiligne: Boolean = false,
) {
    var saisie by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth()) {
        Text(titre, style = MaterialTheme.typography.titleSmall)
        if (aide != null) {
            Text(
                aide,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        valeurs.forEachIndexed { index, valeur ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = if (onModifier == null) Alignment.Top
                else Alignment.CenterVertically,
            ) {
                if (onModifier == null) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(valeur, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(
                        value = valeur,
                        onValueChange = { onModifier(index, it) },
                        modifier = Modifier.weight(1f),
                        singleLine = !multiligne,
                        minLines = if (multiligne) 2 else 1,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = MaterialTheme.shapes.small,
                    )
                }
                IconButton(onClick = { onSupprimer(index) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Supprimer", Modifier.size(18.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = saisie,
                onValueChange = { saisie = it },
                label = { Text("Ajouter") },
                modifier = Modifier.weight(1f),
                singleLine = !multiligne,
                minLines = if (multiligne) 2 else 1,
                shape = MaterialTheme.shapes.small,
            )
            IconButton(
                onClick = {
                    if (saisie.isNotBlank()) { onAjouter(saisie.trim()); saisie = "" }
                },
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            ) {
                Icon(Icons.Default.Add, "Ajouter")
            }
        }
    }
}

/** Remplace l'element a [index], en laissant la liste inchangee si l'index sort. */
fun List<String>.remplacer(index: Int, valeur: String): List<String> =
    if (index !in indices) this else mapIndexed { i, v -> if (i == index) valeur else v }

@Composable
fun Bandeau(
    texte: String,
    type: TypeBandeau = TypeBandeau.INFO,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val (fond, texteCouleur) = when (type) {
        TypeBandeau.INFO -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        TypeBandeau.SUCCES -> Color(0xFFD3EDE9) to Color(0xFF06332F)
        TypeBandeau.ALERTE -> Color(0xFFFDEBD2) to Color(0xFF4A2400)
        TypeBandeau.ERREUR -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = fond,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(texte, color = texteCouleur, style = MaterialTheme.typography.bodyMedium)
            if (action != null) Box(Modifier.padding(top = 6.dp)) { action() }
        }
    }
}

enum class TypeBandeau { INFO, SUCCES, ALERTE, ERREUR }

/** Jauge de score : vert au-dela de 75, ambre entre 50 et 75, rouge en dessous. */
@Composable
fun JaugeScore(
    score: Int,
    libelle: String,
    modifier: Modifier = Modifier,
) {
    val couleur = when {
        score >= 75 -> CouleurSucces
        score >= 50 -> CouleurAlerte
        else -> MaterialTheme.colorScheme.error
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(libelle, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "$score / 100",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = couleur,
            )
        }
        LinearProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = couleur,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
fun EtatVide(
    titre: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(titre, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) action()
    }
}

@Composable
fun TexteDefilant(texte: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            texte,
            modifier = Modifier
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
