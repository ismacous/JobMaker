package com.jobmaker.render

import com.jobmaker.data.model.CvContent
import com.jobmaker.data.model.LetterContent
import com.jobmaker.data.model.Profile

/**
 * Transforme le contenu produit par l'IA en page HTML A4.
 *
 * L'identite (nom, telephone, adresse, email, liens) est prise directement dans
 * le profil et jamais dans la sortie du modele : c'est la garantie mecanique
 * qu'un numero de telephone ne peut pas etre "reformule".
 */
object HtmlRenderer {

    fun renderCv(
        profile: Profile,
        cv: CvContent,
        template: CvTemplate,
        accent: String,
        photoDataUri: String? = null,
    ): String {
        val corps = when (template.id) {
            "moderne" -> corpsDeuxColonnes(profile, cv, photoDataUri)
            else -> corpsUneColonne(profile, cv, photoDataUri)
        }
        return document(
            titre = "CV ${profile.identite.nomComplet}",
            css = template.css,
            accent = accent,
            corps = corps,
        )
    }

    fun renderLettre(
        profile: Profile,
        lettre: LetterContent,
        accent: String,
    ): String {
        val id = profile.identite
        val corps = buildString {
            append("<div class=\"page lettre\">")
            append("<div class=\"expediteur\">")
            append("<div class=\"exp-nom\">${esc(id.nomComplet)}</div>")
            listOf(id.adresse, id.localisation, id.telephone, id.email)
                .filter { it.isNotBlank() }
                .forEach { append("<div>${esc(it)}</div>") }
            append("</div>")

            if (lettre.destinataire.isNotBlank()) {
                append("<div class=\"destinataire\">${escMultiligne(lettre.destinataire)}</div>")
            }
            if (lettre.lieuEtDate.isNotBlank()) {
                append("<div class=\"date\">${esc(lettre.lieuEtDate)}</div>")
            }
            if (lettre.objet.isNotBlank()) {
                append("<div class=\"objet\"><strong>Objet :</strong> ${esc(lettre.objet)}</div>")
            }
            append("<div class=\"salutation\">${esc(lettre.salutation)}</div>")
            lettre.paragraphes.filter { it.isNotBlank() }.forEach {
                append("<p>${esc(it)}</p>")
            }
            if (lettre.formulePolitesse.isNotBlank()) {
                append("<p class=\"politesse\">${esc(lettre.formulePolitesse)}</p>")
            }
            append("<div class=\"signature\">${esc(lettre.signature.ifBlank { id.nomComplet })}</div>")
            append("</div>")
        }
        return document("Lettre ${id.nomComplet}", CSS_LETTRE, accent, corps)
    }

    // -----------------------------------------------------------------------

    private fun document(titre: String, css: String, accent: String, corps: String) = """
<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(titre)}</title>
<style>:root { --accent: ${esc(accent)}; }
$css</style>
</head>
<body>
$corps
</body>
</html>
""".trimIndent()

    // -----------------------------------------------------------------------
    // Mise en page a une colonne (sobre, elegant, compact)
    // -----------------------------------------------------------------------

    private fun corpsUneColonne(profile: Profile, cv: CvContent, photo: String?) = buildString {
        val id = profile.identite
        append("<div class=\"page\">")

        append("<div class=\"entete\">")
        append("<div>")
        append("<h1 class=\"nom\">${esc(id.nomComplet)}</h1>")
        if (cv.titre.isNotBlank()) append("<div class=\"titre-cv\">${esc(cv.titre)}</div>")
        append("<div class=\"contact\">")
        contacts(profile).forEach { append("<span>${esc(it)}</span>") }
        append("</div>")
        append("</div>")
        if (photo != null) append("<img class=\"photo\" src=\"$photo\" alt=\"\">")
        append("</div>")

        if (cv.accroche.isNotBlank()) {
            section("Profil") { append("<div class=\"accroche\">${esc(cv.accroche)}</div>") }
        }

        if (cv.experiences.isNotEmpty()) {
            section("Experience professionnelle") {
                cv.experiences.forEach { append(blocExperience(it)) }
            }
        }

        if (cv.formations.isNotEmpty()) {
            section("Formation") { cv.formations.forEach { append(blocFormation(it)) } }
        }

        if (cv.competences.any { it.items.isNotEmpty() }) {
            section("Competences") {
                cv.competences.filter { it.items.isNotEmpty() }.forEach { g ->
                    append("<div class=\"competence-ligne\">")
                    if (g.categorie.isNotBlank())
                        append("<span class=\"competence-cat\">${esc(g.categorie)} : </span>")
                    append(esc(g.items.joinToString(" · ")))
                    append("</div>")
                }
            }
        }

        if (cv.projets.isNotEmpty()) {
            section("Projets") {
                cv.projets.forEach {
                    append("<div class=\"item\"><span class=\"item-poste\">${esc(it.nom)}</span> — ${esc(it.description)}</div>")
                }
            }
        }

        if (cv.langues.isNotEmpty()) {
            section("Langues") {
                append(cv.langues.joinToString(" · ") { "${esc(it.nom)} (${esc(it.niveau)})" })
            }
        }

        if (cv.certifications.isNotEmpty()) {
            section("Certifications") { append(liste(cv.certifications)) }
        }

        val bas = cv.infosComplementaires + cv.centresInteret
        if (bas.isNotEmpty()) {
            section("Informations complementaires") {
                append(bas.joinToString(" · ") { esc(it) })
            }
        }

        append("</div>")
    }

    // -----------------------------------------------------------------------
    // Mise en page a deux colonnes (moderne)
    // -----------------------------------------------------------------------

    private fun corpsDeuxColonnes(profile: Profile, cv: CvContent, photo: String?) = buildString {
        val id = profile.identite
        append("<div class=\"page\">")

        // --- colonne laterale ---
        append("<aside class=\"colonne-gauche\">")
        if (photo != null) append("<img class=\"photo\" src=\"$photo\" alt=\"\">")
        append("<h1 class=\"nom\">${esc(id.nomComplet)}</h1>")
        if (cv.titre.isNotBlank()) append("<div class=\"titre-cv\">${esc(cv.titre)}</div>")

        append("<div class=\"section\"><div class=\"section-titre\">Contact</div>")
        append("<div class=\"contact\">")
        contacts(profile).forEach { append("<span>${esc(it)}</span>") }
        append("</div></div>")

        if (cv.competences.any { it.items.isNotEmpty() }) {
            append("<div class=\"section\"><div class=\"section-titre\">Competences</div>")
            cv.competences.filter { it.items.isNotEmpty() }.forEach { g ->
                if (g.categorie.isNotBlank())
                    append("<div style=\"font-weight:600;margin-top:5px\">${esc(g.categorie)}</div>")
                append("<div>")
                g.items.forEach { append("<span class=\"pastille\">${esc(it)}</span>") }
                append("</div>")
            }
            append("</div>")
        }

        if (cv.langues.isNotEmpty()) {
            append("<div class=\"section\"><div class=\"section-titre\">Langues</div><ul>")
            cv.langues.forEach { append("<li>${esc(it.nom)} — ${esc(it.niveau)}</li>") }
            append("</ul></div>")
        }

        if (cv.certifications.isNotEmpty()) {
            append("<div class=\"section\"><div class=\"section-titre\">Certifications</div>")
            append(liste(cv.certifications))
            append("</div>")
        }

        val bas = cv.infosComplementaires + cv.centresInteret
        if (bas.isNotEmpty()) {
            append("<div class=\"section\"><div class=\"section-titre\">Divers</div>")
            append(liste(bas))
            append("</div>")
        }
        append("</aside>")

        // --- colonne principale ---
        append("<main class=\"colonne-droite\">")
        if (cv.accroche.isNotBlank()) {
            append("<div class=\"section\"><div class=\"section-titre\">Profil</div>")
            append("<div class=\"accroche\">${esc(cv.accroche)}</div></div>")
        }
        if (cv.experiences.isNotEmpty()) {
            append("<div class=\"section\"><div class=\"section-titre\">Experience professionnelle</div>")
            cv.experiences.forEach { append(blocExperience(it)) }
            append("</div>")
        }
        if (cv.formations.isNotEmpty()) {
            append("<div class=\"section\"><div class=\"section-titre\">Formation</div>")
            cv.formations.forEach { append(blocFormation(it)) }
            append("</div>")
        }
        if (cv.projets.isNotEmpty()) {
            append("<div class=\"section\"><div class=\"section-titre\">Projets</div>")
            cv.projets.forEach {
                append("<div class=\"item\"><span class=\"item-poste\">${esc(it.nom)}</span> — ${esc(it.description)}</div>")
            }
            append("</div>")
        }
        append("</main>")

        append("</div>")
    }

    // -----------------------------------------------------------------------

    private inline fun StringBuilder.section(titre: String, contenu: StringBuilder.() -> Unit) {
        append("<div class=\"section\"><div class=\"section-titre\">${esc(titre)}</div>")
        contenu()
        append("</div>")
    }

    private fun blocExperience(e: com.jobmaker.data.model.CvExperience) = buildString {
        append("<div class=\"item\">")
        append("<div class=\"item-tete\"><div>")
        append("<span class=\"item-poste\">${esc(e.poste)}</span>")
        if (e.entreprise.isNotBlank()) append(" <span class=\"item-entreprise\">— ${esc(e.entreprise)}</span>")
        if (e.lieu.isNotBlank()) append(" <span class=\"item-periode\">(${esc(e.lieu)})</span>")
        append("</div>")
        if (e.periode.isNotBlank()) append("<div class=\"item-periode\">${esc(e.periode)}</div>")
        append("</div>")
        if (e.puces.isNotEmpty()) append(liste(e.puces))
        append("</div>")
    }

    private fun blocFormation(f: com.jobmaker.data.model.CvFormation) = buildString {
        append("<div class=\"item\">")
        append("<div class=\"item-tete\"><div>")
        append("<span class=\"item-poste\">${esc(f.diplome)}</span>")
        if (f.etablissement.isNotBlank()) append(" <span class=\"item-entreprise\">— ${esc(f.etablissement)}</span>")
        append("</div>")
        if (f.periode.isNotBlank()) append("<div class=\"item-periode\">${esc(f.periode)}</div>")
        append("</div>")
        if (f.detail.isNotBlank()) append("<div>${esc(f.detail)}</div>")
        append("</div>")
    }

    private fun liste(items: List<String>) =
        "<ul>" + items.filter { it.isNotBlank() }.joinToString("") { "<li>${esc(it)}</li>" } + "</ul>"

    private fun contacts(profile: Profile): List<String> = with(profile.identite) {
        buildList {
            if (telephone.isNotBlank()) add(telephone)
            if (email.isNotBlank()) add(email)
            val lieu = listOf(adresse, localisation).filter { it.isNotBlank() }.joinToString(", ")
            if (lieu.isNotBlank()) add(lieu)
            profile.liens.filter { it.url.isNotBlank() }.forEach {
                add(if (it.libelle.isNotBlank()) "${it.libelle} : ${it.url}" else it.url)
            }
        }
    }

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun escMultiligne(s: String) = esc(s).replace("\n", "<br>")

    private const val CSS_LETTRE = """
@page { size: A4; margin: 0; }
* { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
html, body { margin: 0; padding: 0; }
body {
  width: 210mm; font-family: 'Roboto', 'Noto Sans', Arial, sans-serif;
  font-size: 11pt; line-height: 1.6; color: #1a1a1a; background: #fff;
}
.page { padding: 22mm 20mm; min-height: 297mm; }
.expediteur { margin-bottom: 16mm; font-size: 10pt; line-height: 1.45; }
.exp-nom { font-weight: 700; font-size: 12.5pt; color: var(--accent); margin-bottom: 2px; }
.destinataire { margin-left: 55%; margin-bottom: 10mm; font-size: 10.5pt; line-height: 1.45; }
.date { text-align: right; margin-bottom: 10mm; font-size: 10.5pt; }
.objet { margin-bottom: 9mm; padding-bottom: 3px; border-bottom: 1.5px solid var(--accent); }
.salutation { margin-bottom: 5mm; }
p { margin: 0 0 4.5mm 0; text-align: justify; }
.politesse { margin-top: 7mm; }
.signature { margin-top: 12mm; text-align: right; font-weight: 600; }
"""
}
