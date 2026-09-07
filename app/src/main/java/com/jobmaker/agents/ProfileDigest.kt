package com.jobmaker.agents

import com.jobmaker.data.model.Profile

/**
 * Profil mis en forme pour le modele, avec la table de correspondance des
 * identifiants.
 *
 * Les experiences sont etiquetees E1, E2... au lieu de leurs UUID : un UUID
 * coute une trentaine de tokens, se recopie mal, et le modele finit par en
 * inventer. On retraduit ensuite les etiquettes vers les vrais identifiants.
 */
data class ProfileDigest(
    val texte: String,
    val idParEtiquette: Map<String, String>,
) {
    fun idReel(etiquette: String): String? =
        idParEtiquette[etiquette.trim().uppercase()]
}

object ProfileSerializer {

    /**
     * @param maxMissionsParExperience limite le detail des experiences quand le
     *   contexte est court. Les experiences les plus recentes gardent tout.
     */
    fun digest(profile: Profile, maxMissionsParExperience: Int = 8): ProfileDigest {
        val ids = mutableMapOf<String, String>()
        val sb = StringBuilder()

        with(profile.identite) {
            sb.appendLine("IDENTITE")
            sb.appendLine("  Nom : $nomComplet")
            if (titre.isNotBlank()) sb.appendLine("  Intitule actuel : $titre")
            if (localisation.isNotBlank()) sb.appendLine("  Localisation : $localisation")
            if (pays.isNotBlank()) sb.appendLine("  Pays : $pays")
            if (dateNaissance.isNotBlank()) sb.appendLine("  Naissance : $dateNaissance")
            if (nationalite.isNotBlank()) sb.appendLine("  Nationalite : $nationalite")
        }

        if (profile.presentation.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("PRESENTATION LIBRE (mots du candidat)")
            sb.appendLine("  ${profile.presentation.trim()}")
        }

        if (profile.objectifPro.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("OBJECTIF PROFESSIONNEL")
            sb.appendLine("  ${profile.objectifPro.trim()}")
        }

        if (profile.experiences.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("EXPERIENCES PROFESSIONNELLES")
            profile.experiences.forEachIndexed { index, exp ->
                val tag = "E${index + 1}"
                ids[tag] = exp.id
                sb.appendLine("  [$tag] ${exp.poste.ifBlank { "Poste non precise" }}")
                sb.appendLine("       Entreprise : ${exp.entreprise.ifBlank { "non precisee" }}")
                if (exp.secteur.isNotBlank()) sb.appendLine("       Secteur : ${exp.secteur}")
                if (exp.lieu.isNotBlank()) sb.appendLine("       Lieu : ${exp.lieu}")
                if (exp.typeContrat.isNotBlank()) sb.appendLine("       Contrat : ${exp.typeContrat}")
                if (exp.periode.isNotBlank()) sb.appendLine("       Periode : ${exp.periode}")
                if (exp.contexte.isNotBlank()) sb.appendLine("       Contexte : ${exp.contexte}")
                exp.missions.take(maxMissionsParExperience).forEach {
                    sb.appendLine("       - mission : $it")
                }
                exp.realisations.take(maxMissionsParExperience).forEach {
                    sb.appendLine("       - resultat : $it")
                }
                if (exp.outils.isNotEmpty())
                    sb.appendLine("       Outils : ${exp.outils.joinToString(", ")}")
            }
        }

        if (profile.formations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("FORMATION")
            profile.formations.forEach { f ->
                sb.append("  - ${f.diplome.ifBlank { "Formation" }}")
                if (f.etablissement.isNotBlank()) sb.append(", ${f.etablissement}")
                if (f.lieu.isNotBlank()) sb.append(" (${f.lieu})")
                if (f.periode.isNotBlank()) sb.append(" | ${f.periode}")
                if (f.niveau.isNotBlank()) sb.append(" | niveau ${f.niveau}")
                if (f.mention.isNotBlank()) sb.append(" | mention ${f.mention}")
                sb.appendLine()
                if (f.matieres.isNotEmpty())
                    sb.appendLine("      Matieres : ${f.matieres.joinToString(", ")}")
            }
        }

        if (profile.competences.any { it.items.isNotEmpty() }) {
            sb.appendLine()
            sb.appendLine("COMPETENCES")
            profile.competences.filter { it.items.isNotEmpty() }.forEach { groupe ->
                val items = groupe.items.joinToString(", ") { c ->
                    buildString {
                        append(c.nom)
                        if (c.niveau > 0) append(" (niveau ${c.niveau}/5")
                        if (c.anneesExperience > 0) {
                            if (c.niveau > 0) append(", ") else append(" (")
                            append("${c.anneesExperience} an(s)")
                        }
                        if (c.niveau > 0 || c.anneesExperience > 0) append(")")
                    }
                }
                sb.appendLine("  ${groupe.categorie.ifBlank { "Autres" }} : $items")
            }
        }

        if (profile.langues.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("LANGUES")
            profile.langues.forEach { l ->
                sb.append("  - ${l.nom} : ${l.niveau.ifBlank { "niveau non precise" }}")
                if (l.certification.isNotBlank()) sb.append(" (${l.certification})")
                sb.appendLine()
            }
        }

        if (profile.certifications.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("CERTIFICATIONS")
            profile.certifications.forEach { c ->
                sb.appendLine("  - ${c.nom} ${c.organisme.let { if (it.isNotBlank()) "- $it" else "" }} ${c.annee}".trim())
            }
        }

        if (profile.projets.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("PROJETS")
            profile.projets.forEach { p ->
                sb.appendLine("  - ${p.nom} : ${p.description}")
                if (p.role.isNotBlank()) sb.appendLine("      Role : ${p.role}")
                if (p.outils.isNotEmpty()) sb.appendLine("      Outils : ${p.outils.joinToString(", ")}")
            }
        }

        if (profile.benevolat.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("BENEVOLAT / ASSOCIATIF")
            profile.benevolat.forEach { b ->
                sb.appendLine("  - ${b.resumeLigne} ${b.periode}".trim())
                b.missions.take(3).forEach { sb.appendLine("      - $it") }
            }
        }

        val divers = buildList {
            if (profile.permis.isNotEmpty()) add("Permis : ${profile.permis.joinToString(", ")}")
            if (profile.recherche.vehicule) add("Vehicule personnel")
            if (profile.centresInteret.isNotEmpty())
                add("Centres d'interet : ${profile.centresInteret.joinToString(", ")}")
            profile.liens.filter { it.url.isNotBlank() }.forEach { add("${it.libelle} : ${it.url}") }
        }
        if (divers.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("DIVERS")
            divers.forEach { sb.appendLine("  - $it") }
        }

        with(profile.recherche) {
            val lignes = buildList {
                if (posteVise.isNotBlank()) add("Poste vise : $posteVise")
                if (secteursVises.isNotEmpty()) add("Secteurs vises : ${secteursVises.joinToString(", ")}")
                if (disponibilite.isNotBlank()) add("Disponibilite : $disponibilite")
                if (mobilite.isNotBlank()) add("Mobilite : $mobilite")
                if (teletravail.isNotBlank()) add("Teletravail : $teletravail")
                if (contraintes.isNotBlank()) add("Contraintes : $contraintes")
            }
            if (lignes.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("CONTEXTE DE RECHERCHE (ne s'imprime pas sur le CV)")
                lignes.forEach { sb.appendLine("  - $it") }
            }
        }

        return ProfileDigest(sb.toString().trimEnd(), ids)
    }

    /**
     * Version reduite, utilisee quand le prompt complet ne rentre pas dans la
     * fenetre de contexte du modele choisi.
     */
    fun digestCourt(profile: Profile): ProfileDigest = digest(profile, maxMissionsParExperience = 3)
}
