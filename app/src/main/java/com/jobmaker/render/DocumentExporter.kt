package com.jobmaker.render

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.jobmaker.data.model.Candidature
import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Production des fichiers a envoyer aux recruteurs. */
class DocumentExporter(private val context: Context) {

    private val pdf = PdfExporter(context)

    val dossierExport: File
        get() = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }

    fun htmlCv(profile: Profile, candidature: Candidature, gabaritId: String = candidature.gabarit): String {
        val gabarit = CvTemplates.byId(gabaritId)
        val photo = if (candidature.avecPhoto) pdf.photoEnDataUri(profile.identite.photoUri) else null
        return HtmlRenderer.renderCv(profile, candidature.cv, gabarit, candidature.couleurAccent, photo)
    }

    fun htmlLettre(profile: Profile, candidature: Candidature): String =
        HtmlRenderer.renderLettre(profile, candidature.lettre, candidature.couleurAccent)

    /**
     * Ouvre la boite de dialogue d'impression sur le CV. L'utilisateur y
     * choisit « Enregistrer au format PDF ».
     *
     * Retourne null si tout va bien, sinon le message d'erreur a afficher.
     */
    suspend fun imprimerCv(activity: Activity, profile: Profile, candidature: Candidature): String? =
        pdf.imprimer(activity, htmlCv(profile, candidature), nomFichier("CV", profile, candidature))

    suspend fun imprimerLettre(
        activity: Activity,
        profile: Profile,
        candidature: Candidature,
    ): String? = pdf.imprimer(
        activity,
        htmlLettre(profile, candidature),
        nomFichier("Lettre de motivation", profile, candidature),
    )

    /** A appeler quand l'ecran d'impression est refermé. */
    fun libererImpression() = pdf.liberer()

    /**
     * Version texte brut. Beaucoup de formulaires de candidature en ligne
     * demandent de coller le CV dans un champ texte : un copier-coller depuis
     * un PDF y arrive en desordre.
     */
    suspend fun exporterCvTexte(profile: Profile, candidature: Candidature): File =
        withContext(Dispatchers.IO) {
            val nom = nomFichier("CV", profile, candidature) + ".txt"
            val fichier = File(dossierExport, nom)
            fichier.writeText(texteCv(profile, candidature.cv))
            fichier
        }

    suspend fun exporterLettreTexte(profile: Profile, candidature: Candidature): File =
        withContext(Dispatchers.IO) {
            val nom = nomFichier("Lettre de motivation", profile, candidature) + ".txt"
            val fichier = File(dossierExport, nom)
            fichier.writeText(texteLettre(profile, candidature.lettre))
            fichier
        }

    fun texteCv(profile: Profile, cv: CvContent): String = buildString {
        val id = profile.identite
        appendLine(id.nomComplet.uppercase())
        if (cv.titre.isNotBlank()) appendLine(cv.titre)
        appendLine(listOf(id.telephone, id.email, id.localisation).filter { it.isNotBlank() }
            .joinToString(" | "))
        profile.liens.filter { it.url.isNotBlank() }.forEach { appendLine("${it.libelle} : ${it.url}") }
        appendLine()

        if (cv.accroche.isNotBlank()) {
            appendLine("PROFIL"); appendLine(cv.accroche); appendLine()
        }
        if (cv.experiences.isNotEmpty()) {
            appendLine("EXPERIENCE PROFESSIONNELLE")
            cv.experiences.forEach { e ->
                appendLine()
                appendLine(listOf(e.poste, e.entreprise, e.lieu).filter { it.isNotBlank() }
                    .joinToString(" - ") + (if (e.periode.isNotBlank()) "  (${e.periode})" else ""))
                e.puces.forEach { appendLine("- $it") }
            }
            appendLine()
        }
        if (cv.formations.isNotEmpty()) {
            appendLine("FORMATION")
            cv.formations.forEach { f ->
                appendLine("- " + listOf(f.diplome, f.etablissement, f.lieu, f.periode)
                    .filter { it.isNotBlank() }.joinToString(", "))
                if (f.detail.isNotBlank()) appendLine("  ${f.detail}")
            }
            appendLine()
        }
        if (cv.competences.any { it.items.isNotEmpty() }) {
            appendLine("COMPETENCES")
            cv.competences.filter { it.items.isNotEmpty() }.forEach {
                appendLine("- ${it.categorie} : ${it.items.joinToString(", ")}")
            }
            appendLine()
        }
        if (cv.langues.isNotEmpty()) {
            appendLine("LANGUES")
            cv.langues.forEach { appendLine("- ${it.nom} : ${it.niveau}") }
            appendLine()
        }
        if (cv.certifications.isNotEmpty()) {
            appendLine("CERTIFICATIONS")
            cv.certifications.forEach { appendLine("- $it") }
            appendLine()
        }
        val bas = cv.infosComplementaires + cv.centresInteret
        if (bas.isNotEmpty()) {
            appendLine("INFORMATIONS COMPLEMENTAIRES")
            bas.forEach { appendLine("- $it") }
        }
    }

    fun texteLettre(profile: Profile, lettre: LetterContent): String = buildString {
        val id = profile.identite
        appendLine(id.nomComplet)
        listOf(id.adresse, id.localisation, id.telephone, id.email)
            .filter { it.isNotBlank() }.forEach { appendLine(it) }
        appendLine()
        if (lettre.destinataire.isNotBlank()) { appendLine(lettre.destinataire); appendLine() }
        if (lettre.lieuEtDate.isNotBlank()) { appendLine(lettre.lieuEtDate); appendLine() }
        if (lettre.objet.isNotBlank()) { appendLine("Objet : ${lettre.objet}"); appendLine() }
        appendLine(lettre.salutation)
        appendLine()
        lettre.paragraphes.filter { it.isNotBlank() }.forEach { appendLine(it); appendLine() }
        if (lettre.formulePolitesse.isNotBlank()) { appendLine(lettre.formulePolitesse); appendLine() }
        appendLine(lettre.signature.ifBlank { id.nomComplet })
    }

    fun uriPartage(fichier: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)

    fun intentPartage(fichier: File, typeMime: String, sujet: String): Intent {
        val uri = uriPartage(fichier)
        return Intent(Intent.ACTION_SEND).apply {
            type = typeMime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, sujet)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun intentOuvrir(fichier: File, typeMime: String): Intent {
        val uri = uriPartage(fichier)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, typeMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun nomFichier(prefixe: String, profile: Profile, candidature: Candidature): String {
        val personne = profile.identite.nomComplet.ifBlank { "Candidat" }
        val poste = candidature.analyse.poste.take(45).ifBlank { "Poste" }
        val entreprise = candidature.analyse.entreprise.take(30)
        val parts = listOf(prefixe, personne, poste, entreprise).filter { it.isNotBlank() }
        return parts.joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
