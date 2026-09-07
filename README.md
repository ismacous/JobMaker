# JobMaker

Application Android personnelle qui rédige un CV et une lettre de motivation
**adaptés à chaque offre d'emploi**, avec une IA qui tourne **entièrement sur le
téléphone**.

Aucun compte, aucun abonnement, aucun serveur. Votre profil, les offres que vous
collez et les documents produits ne quittent jamais l'appareil. La seule
connexion réseau de l'application est le téléchargement du modèle d'IA, une
fois.

---

## Ce que fait l'application

1. **Profil** — vous saisissez une fois, en détail, tout ce qui vous concerne :
   identité, expériences, formation, compétences, langues, certifications,
   projets, bénévolat, permis, contraintes de recherche.
2. **Candidater** — vous collez une offre d'emploi (Indeed, France Travail,
   LinkedIn, une annonce en vitrine…). Six agents IA s'enchaînent et produisent
   un CV et une lettre taillés pour cette offre précise.
3. **Documents** — aperçu fidèle au PDF final, retouche manuelle de chaque
   ligne, choix parmi quatre mises en page, export PDF ou texte, partage.
4. **Comprendre** — vous collez une annonce dont vous ne comprenez pas
   l'intitulé et l'application vous explique le métier, le quotidien réel,
   l'ordre de grandeur du salaire, les questions à poser en entretien et les
   signaux d'alerte.

---

## Le pipeline : six agents, pas un seul

Une seule requête à un modèle qui doit tout faire d'un coup donne un CV
générique. L'application découpe donc le travail en étapes, chacune avec sa
consigne et ses réglages :

| # | Agent | Ce qu'il fait |
|---|-------|---------------|
| 1 | **Analyste d'offre** | Extrait les exigences réelles, les mots-clés à replacer mot pour mot, et ce que l'annonce attend sans l'écrire. Si l'annonce fait trois lignes, il complète avec ce que le métier implique habituellement et le signale. |
| 2 | **Stratège** | Décide de l'angle : quelles expériences mettre en avant, dans quel ordre, et comment répondre honnêtement aux exigences non couvertes. |
| 3 | **Rédacteur CV** | Réécrit les expériences pour cette offre : verbes d'action, résultats chiffrés, mots-clés placés là où ils comptent. |
| 4 | **Rédacteur lettre** | Structure imposée en quatre paragraphes — le besoin de l'entreprise, la preuve tirée du parcours, l'apport concret, la conclusion. 250 à 330 mots. |
| 5 | **Relecteur** | Cherche ce qui ferait écarter le dossier : inventions, mots-clés oubliés, hors-sujet, puces sans verbe, lettre trop longue. |
| 6 | **Correcteur** | Applique les corrections bloquantes et importantes. |

### La règle qui compte le plus : l'IA n'a pas le droit d'inventer

Un CV avec un employeur, un diplôme ou un chiffre imaginaire ne fait pas perdre
qu'un poste : il fait perdre la crédibilité dès le premier entretien. Trois
garde-fous, dont deux ne dépendent pas de l'IA :

- **Dans les consignes** — chaque agent de rédaction reçoit l'interdiction
  explicite d'ajouter un employeur, une école, une date, un chiffre ou une
  compétence absente du profil. Il peut reformuler, trier, traduire, expliciter.
  Rien de plus.
- **Vérification mécanique** — après génération, l'application compare le CV au
  profil, sans IA : chaque employeur et chaque établissement doit correspondre à
  une organisation réellement déclarée, chaque chiffre doit se retrouver dans le
  profil. Ce qui ne colle pas est signalé et renvoyé en correction.
- **L'identité n'est jamais générée** — nom, téléphone, adresse, email et liens
  sont injectés directement depuis votre profil au moment du rendu. Le modèle
  n'a aucun champ où les écrire, donc aucun moyen de les altérer.

L'onglet **Relecture** de chaque candidature affiche ce qui a été trouvé.
Relisez toujours avant d'envoyer : c'est votre nom qui part.

---

## Installation

Il n'y a pas de version sur le Play Store : l'application est faite pour un seul
utilisateur, il faut donc la compiler soi-même une fois.

### Ce qu'il faut

- **Android Studio** (version récente, Ladybug ou plus récent)
- Depuis Android Studio → *Settings → Languages & Frameworks → Android SDK →
  SDK Tools*, cocher et installer :
  - **NDK (Side by side)** version **27.2.12479018**
  - **CMake** version **3.22.1**
- Un câble USB et le **débogage USB** activé sur le téléphone
  (*Paramètres → À propos → appuyer 7 fois sur « Numéro de build »*, puis
  *Options pour les développeurs → Débogage USB*)

### Compiler et installer

```bash
git clone https://github.com/ismacous/JobMaker.git
cd JobMaker
./gradlew installDebug        # téléphone branché en USB
```

Ou, dans Android Studio : ouvrir le dossier, laisser la synchronisation Gradle
se terminer, choisir le téléphone dans la liste des appareils, appuyer sur *Run*.

La **première compilation est longue** (10 à 25 minutes) : le moteur
d'inférence llama.cpp est téléchargé et compilé en code natif pour le
processeur du téléphone. Les compilations suivantes prennent quelques secondes.

### Au premier lancement

1. L'écran d'accueil propose de télécharger le modèle recommandé
   (**Qwen3 4B Instruct Q4**, environ 2,5 Go). À faire en Wi-Fi.
2. Pendant le téléchargement, remplissez l'onglet **Profil**. C'est l'étape la
   plus longue et de loin la plus rentable : tout le reste en dépend.
3. Collez une offre dans **Candidater** et lancez.

---

## Quel modèle choisir

Le S25 Ultra a 12 Go de RAM. C'est la RAM, pas le stockage, qui limite : un
modèle doit tenir en mémoire pendant qu'il travaille.

| Modèle | Taille | Pour quoi |
|--------|--------|-----------|
| **Qwen3 4B Instruct Q4** | 2,5 Go | **Le choix par défaut.** Bon français, suit précisément les consignes, rend du JSON propre. Suffit à lui seul pour tout. |
| Qwen3 4B Instruct Q5 | 3,0 Go | Même modèle, quantification plus fine. Formulations un peu plus justes. À préférer pour la rédaction, puisque le stockage n'est pas un problème. |
| Qwen3 8B Q4 | 5,0 Go | Rédaction sensiblement meilleure et meilleur raisonnement sur les offres vagues. Passe sur 12 Go de RAM en fermant les autres applications. 2 à 4 fois plus lent. |
| Gemma 3 4B Q4 | 2,5 Go | Lettres au style plus naturel, moins scolaire. Moins fiable sur le JSON. |
| Qwen3 1.7B Q4 | 1,1 Go | Rapide. Bon pour l'analyse d'offres, insuffisant pour rédiger. |
| Mistral Nemo 12B Q4 | 7,5 Go | Le meilleur français écrit, mais au-delà de ce qu'un téléphone de 12 Go tient confortablement. À tester en dernier. |

Vous pouvez **affecter un modèle différent à chaque étape** (*Réglages →
Modèles par étape*) : par exemple le 1.7B pour l'analyse et le 8B pour la
rédaction. Attention, un seul modèle tient en mémoire à la fois : changer de
modèle entre deux étapes coûte quelques secondes de rechargement. Le réglage le
plus rapide reste le même modèle partout.

Ordres de grandeur sur S25 Ultra, une candidature complète avec relecture :
**2 à 5 minutes** avec le 4B, **8 à 20 minutes** avec le 8B.

---

## Les quatre mises en page

| Gabarit | Robots de tri | Pour |
|---------|---------------|------|
| **Sobre** | ✅ sûr | Candidatures déposées sur un portail (Indeed, sites de grands groupes). Le choix par défaut. |
| **Compact** | ✅ sûr | Long parcours à faire tenir sur une page. |
| **Élégant** | ✅ sûr | Droit, édition, conseil. |
| **Moderne** | ⚠️ risqué | Bandeau latéral coloré. Très lisible pour un humain, mais beaucoup de logiciels de tri lisent les deux colonnes en désordre. À réserver aux envois directs par mail ou en main propre. |

Règle pratique : **portail en ligne → Sobre. Mail à une personne → ce que vous
voulez.**

---

## Confidentialité

- Aucune donnée personnelle n'est envoyée nulle part. L'application ne contacte
  que HuggingFace, uniquement pour télécharger les fichiers de modèle, et ne
  transmet alors rien vous concernant.
- Tout est stocké dans l'espace privé de l'application. Une désinstallation
  efface tout.
- **Exportez votre profil régulièrement** : *Profil → Sauvegarde du profil →
  Exporter*. C'est votre seule protection en cas de perte du téléphone.

---

## En cas de problème

**« Aucun modèle installé »** → *Réglages → Modèles d'IA* → télécharger.

**Le téléchargement échoue (erreur 404)** → le dépôt HuggingFace a été renommé.
Deux solutions : essayer un autre modèle du catalogue, ou télécharger le fichier
`.gguf` depuis un ordinateur et l'importer avec *Modèles d'IA → Importer un
fichier .gguf*. N'importe quel modèle au format GGUF fonctionne.

**L'application se ferme pendant une génération** → manque de mémoire. Dans
l'ordre : fermer les autres applications, réduire la *fenêtre de contexte*
(*Réglages → Performance*, essayer 4096), puis prendre un modèle plus petit.

**« Texte trop long pour la fenêtre de contexte »** → raccourcir l'offre collée
(garder l'intitulé, les missions et le profil recherché ; jeter les paragraphes
sur la culture d'entreprise), ou augmenter la fenêtre de contexte.

**« Le modèle n'a pas produit de JSON exploitable »** → le modèle est trop petit
pour cette offre. Passer au 4B si vous utilisez le 1.7B, ou au 8B.

**C'est lent** → normal, tout est calculé localement. Le téléphone se bride
aussi quand il chauffe : laisser l'écran allumé et ne pas le tenir en main
pendant la génération aide.

**La compilation native échoue** → voir `docs/BUILD.md`.

---

## Documentation

- [`docs/BUILD.md`](docs/BUILD.md) — compilation détaillée, options du moteur
  natif, problèmes connus
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — organisation du code et
  décisions techniques

## Tests

```bash
./gradlew testDebugUnitTest
```

24 tests couvrent les parties où une régression serait la plus coûteuse : la
récupération du JSON produit par le modèle, la détection des inventions, et le
rendu des documents.
