package com.jobmaker.render

/**
 * Gabarits de mise en page.
 *
 * Le drapeau [atsSafe] n'est pas cosmetique : beaucoup de logiciels de tri de
 * candidatures lisent le PDF colonne par colonne et melangent le texte des
 * mises en page a deux colonnes. Un CV en deux colonnes est plus agreable a
 * l'oeil humain mais peut ressortir illisible d'un robot. D'ou la regle
 * pratique proposee dans l'application : gabarit sobre pour les candidatures
 * deposees sur un portail (Indeed, Welcome to the Jungle, sites de grands
 * groupes), gabarit design pour un envoi direct par mail ou en main propre.
 */
data class CvTemplate(
    val id: String,
    val nom: String,
    val description: String,
    val atsSafe: Boolean,
    val css: String,
)

object CvTemplates {

    val tous: List<CvTemplate> by lazy { listOf(sobre, moderne, elegant, compact) }

    fun byId(id: String): CvTemplate = tous.firstOrNull { it.id == id } ?: sobre

    /** Reglages communs : format A4, marges d'impression, typographie de base. */
    private const val BASE = """
@page { size: A4; margin: 0; }
* { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
html, body { margin: 0; padding: 0; }
body {
  width: 210mm;
  font-family: 'Roboto', 'Noto Sans', Arial, sans-serif;
  color: #1a1a1a;
  font-size: 10.2pt;
  line-height: 1.42;
  background: #fff;
}
.page { padding: 13mm 14mm; min-height: 297mm; }
a { color: inherit; text-decoration: none; }
ul { margin: 3px 0 0 0; padding-left: 15px; }
li { margin-bottom: 2.5px; }
h1, h2, h3 { margin: 0; font-weight: 700; }
.nom { font-size: 21pt; letter-spacing: 0.4px; }
.titre-cv { font-size: 11.5pt; font-weight: 600; margin-top: 2px; }
.contact { font-size: 9pt; }
.contact span + span::before { content: "  ·  "; opacity: 0.5; }
.section { margin-top: 11px; }
.section-titre {
  font-size: 10pt; font-weight: 700; text-transform: uppercase;
  letter-spacing: 1.1px; margin-bottom: 5px;
}
.item { margin-bottom: 8px; page-break-inside: avoid; }
.item-tete { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.item-poste { font-weight: 700; }
.item-entreprise { font-weight: 500; }
.item-periode { font-size: 8.8pt; white-space: nowrap; opacity: 0.75; }
.accroche { text-align: justify; }
.pastille {
  display: inline-block; padding: 1.5px 7px; margin: 0 4px 4px 0;
  border-radius: 9px; font-size: 8.8pt;
}
.photo { width: 27mm; height: 34mm; object-fit: cover; border-radius: 3px; }
"""

    // -----------------------------------------------------------------------

    val sobre = CvTemplate(
        id = "sobre",
        nom = "Sobre",
        description = "Une colonne, lisible par tous les logiciels de tri. Le choix sur pour les candidatures en ligne.",
        atsSafe = true,
        css = BASE + """
.entete { border-bottom: 2.5px solid var(--accent); padding-bottom: 8px; margin-bottom: 4px;
          display: flex; justify-content: space-between; gap: 10mm; align-items: flex-start; }
.nom { color: var(--accent); }
.titre-cv { color: #333; text-transform: uppercase; letter-spacing: 0.8px; font-size: 10.5pt; }
.contact { margin-top: 5px; color: #444; }
.section-titre { color: var(--accent); border-bottom: 1px solid #d8d8d8; padding-bottom: 2px; }
.pastille { background: #f0f2f5; border: 1px solid #e0e3e8; }
.competence-ligne { margin-bottom: 3px; }
.competence-cat { font-weight: 600; }
""",
    )

    val moderne = CvTemplate(
        id = "moderne",
        nom = "Moderne",
        description = "Bandeau lateral colore. Tres lisible pour un humain ; a reserver aux envois par mail.",
        atsSafe = false,
        css = BASE + """
.page { padding: 0; display: flex; min-height: 297mm; }
.colonne-gauche {
  width: 68mm; background: var(--accent); color: #fff;
  padding: 13mm 8mm; flex-shrink: 0;
}
.colonne-droite { flex: 1; padding: 13mm 10mm 13mm 9mm; }
.colonne-gauche .section-titre {
  color: #fff; border-bottom: 1px solid rgba(255,255,255,0.45); padding-bottom: 2px;
}
.colonne-gauche .contact { color: rgba(255,255,255,0.94); font-size: 8.8pt; }
.colonne-gauche .contact span { display: block; margin-bottom: 3px; }
.colonne-gauche .contact span + span::before { content: ""; }
.colonne-gauche li { margin-bottom: 3px; }
.colonne-gauche .pastille { background: rgba(255,255,255,0.16); }
.nom { color: #fff; font-size: 18pt; line-height: 1.15; }
.titre-cv { color: rgba(255,255,255,0.92); font-size: 10pt; margin-bottom: 10px; }
.colonne-droite .section-titre { color: var(--accent); }
.colonne-droite .section-titre::after {
  content: ""; display: block; width: 26px; height: 2.5px;
  background: var(--accent); margin-top: 3px;
}
.photo { width: 100%; height: auto; aspect-ratio: 1; border-radius: 50%;
         object-fit: cover; margin-bottom: 9px; border: 2.5px solid rgba(255,255,255,0.65); }
""",
    )

    val elegant = CvTemplate(
        id = "elegant",
        nom = "Elegant",
        description = "Titres en serif, en-tete centre. Convient aux metiers du droit, de l'edition, du conseil.",
        atsSafe = true,
        css = BASE + """
body { font-size: 10pt; }
.entete { text-align: center; padding-bottom: 10px; margin-bottom: 6px;
          border-bottom: 1px solid #c9c9c9; }
.nom { font-family: 'Noto Serif', Georgia, serif; font-weight: 400;
       font-size: 23pt; letter-spacing: 2.5px; text-transform: uppercase; }
.titre-cv { font-family: 'Noto Serif', Georgia, serif; font-style: italic;
            font-weight: 400; color: var(--accent); font-size: 11pt; }
.contact { margin-top: 6px; color: #555; }
.section-titre {
  font-family: 'Noto Serif', Georgia, serif; text-align: center;
  color: var(--accent); letter-spacing: 2.5px; font-weight: 400;
  border-top: 1px solid #e2e2e2; border-bottom: 1px solid #e2e2e2;
  padding: 3px 0; margin-bottom: 7px;
}
.item { padding-left: 12px; border-left: 2px solid #ececec; position: relative; }
.item::before {
  content: ""; position: absolute; left: -3.5px; top: 6px;
  width: 5px; height: 5px; border-radius: 50%; background: var(--accent);
}
.pastille { background: transparent; border: 1px solid var(--accent); color: var(--accent); }
""",
    )

    val compact = CvTemplate(
        id = "compact",
        nom = "Compact",
        description = "Typographie resserree pour faire tenir un long parcours sur une page. Reste lisible par les robots.",
        atsSafe = true,
        css = BASE + """
body { font-size: 9.3pt; line-height: 1.34; }
.page { padding: 10mm 12mm; }
.entete { display: flex; justify-content: space-between; align-items: flex-start;
          gap: 8mm; background: #f5f6f8; padding: 8px 10px; border-left: 4px solid var(--accent); }
.nom { font-size: 17pt; color: var(--accent); }
.titre-cv { font-size: 10pt; }
.section { margin-top: 8px; }
.section-titre { color: #fff; background: var(--accent); padding: 2px 7px;
                 font-size: 8.8pt; letter-spacing: 0.9px; }
.item { margin-bottom: 6px; }
.item-periode { font-size: 8.2pt; }
ul { padding-left: 13px; }
li { margin-bottom: 1.5px; }
.pastille { background: #eef1f5; font-size: 8.2pt; padding: 1px 6px; }
""",
    )

    /** Palette proposee dans l'editeur. */
    val couleurs = listOf(
        "#1F4E79" to "Bleu marine",
        "#0F766E" to "Vert sapin",
        "#7C2D12" to "Brique",
        "#374151" to "Gris ardoise",
        "#5B21B6" to "Prune",
        "#B45309" to "Ambre",
        "#000000" to "Noir",
    )
}
