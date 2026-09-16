# JobMaker

Application Android personnelle qui rédige un CV et une lettre de motivation
**adaptés à chaque offre d'emploi**.

Aucun compte JobMaker, aucun abonnement, aucun serveur : l'application choisit
entre deux moteurs, et c'est vous qui décidez.

| Moteur | Durée par candidature | Ce qui sort du téléphone |
|--------|----------------------|--------------------------|
| **API gratuite** (Groq, Google Gemini, OpenRouter) | quelques secondes | L'offre et le résumé de votre profil partent chez le fournisseur choisi. Clé gratuite, sans carte bancaire. |
| **Sur l'appareil** (llama.cpp) | 2 à 10 minutes | Rien. Nécessite de télécharger un modèle de ~2,5 Go, une fois. |

Le réglage est dans *Réglages → Moteur d'IA* et se change à tout moment. Aucune
clé n'est embarquée dans l'application : voir
[`docs/SECURITE.md`](docs/SECURITE.md).

---

## Ce que fait l'application

1. **Profil** — vous saisissez une fois, en détail, tout ce qui vous concerne :
   identité, expériences, formation, compétences, langues, certifications,
   projets, bénévolat, permis, contraintes de recherche.
2. **Candidater** — vous collez une offre d'emploi (Indeed, France Travail,
   LinkedIn, une annonce en vitrine…). Le pipeline produit un CV et une lettre
   taillés pour cette offre précise — en quelques secondes via une API
   gratuite, en quelques minutes avec le modèle embarqué.
3. **Documents** — aperçu fidèle au PDF final, retouche manuelle de chaque
   ligne, choix parmi quatre mises en page, export PDF, copie en texte brut
   pour les formulaires en ligne. Le bouton **PDF** ouvre l'impression
   d'Android : choisissez **« Enregistrer au format PDF »** et l'emplacement.
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

## Installation — depuis le téléphone seulement

Il n'y a pas de version sur le Play Store : l'application n'est faite que pour
vous. Mais **vous n'avez pas besoin d'ordinateur** : GitHub la compile sur ses
serveurs et vous donne un fichier à installer.

### 1. Lancer la compilation (une fois)

Depuis le navigateur du téléphone :

1. Aller sur **github.com/ismacous/JobMaker**, se connecter.
2. Onglet **Actions** (si le menu est replié, appuyer sur `☰`).
3. Dans la colonne de gauche, choisir **« Construire l'APK »**.
4. Bouton **« Run workflow »** → **« Run workflow »**.

La compilation prend **15 à 25 minutes** (le moteur d'IA est compilé en code
natif). Vous pouvez fermer le navigateur, ça continue sur les serveurs de
GitHub.

En réalité, une compilation part déjà automatiquement à chaque modification du
code : il y a donc peut-être déjà un fichier prêt. Vérifiez l'étape 2 avant de
lancer quoi que ce soit.

### 2. Installer l'application

1. Aller sur **github.com/ismacous/JobMaker/releases/latest**
   (à mettre en favori : ce lien pointe toujours vers la dernière version).
2. Appuyer sur **JobMaker.apk** pour le télécharger.
3. Ouvrir le fichier téléchargé (notification de téléchargement, ou
   *My Files → Téléchargements*).
4. Android affiche « Par mesure de sécurité, votre téléphone n'est pas
   autorisé… » → **Paramètres** → activer l'autorisation pour le navigateur →
   revenir en arrière → **Installer**.
5. Un avertissement Play Protect peut apparaître : **Installer quand même**.
   C'est normal pour une application qui ne vient pas du Play Store.

Les mises à jour s'installent **par-dessus** la précédente sans effacer votre
profil ni vos candidatures : l'application est signée avec une clé stable
versionnée dans le dépôt.

### 3. Mettre l'IA dedans

Deux chemins. Le premier prend deux minutes, le second une demi-heure de
téléchargement. Les deux se font depuis le téléphone, sans ordinateur.

#### A. Brancher une API gratuite — recommandé

1. *Réglages → Moteur d'IA* → **Fournisseur** → **Groq** (offre gratuite la plus
   généreuse, et le seul des trois à annoncer qu'il n'entraîne pas ses modèles
   sur ce que vous envoyez).
2. **Obtenir une clé gratuite** ouvre `console.groq.com/keys` dans le
   navigateur : compte par e-mail, sans carte bancaire. Copier la clé.
3. La coller dans le champ **Coller la clé** → **Enregistrer**. Elle est
   aussitôt chiffrée par le matériel du téléphone.
4. **Tester la connexion** : un aller-retour réel qui vérifie clé, modèle et
   réseau d'un coup.

Si le fournisseur retire le modèle par défaut — cela arrive tous les quelques
mois — *Changer de modèle* interroge l'API et affiche ceux réellement
disponibles avec votre clé.

#### B. Télécharger un modèle embarqué

1. *Réglages → Moteur d'IA* → mode **Sur l'appareil**, puis **Gérer les
   modèles**. Le modèle recommandé est **Qwen3 4B Instruct Q4**, environ 2,5 Go.
2. **Gardez l'application ouverte** pendant le téléchargement. S'il
   s'interrompt (écran verrouillé longtemps, Wi-Fi coupé), il reprend là où il
   s'était arrêté au relancement — rien n'est perdu.
3. Profitez de l'attente pour remplir l'onglet **Profil**. C'est l'étape la
   plus longue et de loin la plus rentable : tout le reste en dépend.

Ensuite, plus besoin d'internet du tout.

> Les deux se combinent, et les clés s'additionnent : le coffre en garde une par
> fournisseur. Quand le quota de l'un est épuisé, *Filet de sécurité* passe au
> suivant dont vous avez enregistré une clé, puis au modèle du téléphone en
> dernier recours — au lieu de tout perdre. L'écran *Moteur d'IA* affiche la
> chaîne exacte.
>
> Utile à savoir : les offres gratuites limitent sur **deux axes à la fois**, et
> les deux fournisseurs sont complémentaires. Groq est généreux en requêtes par
> jour mais serré en tokens par minute — c'est ce mur-là que l'on touche sur une
> longue annonce. Gemini fait l'inverse : très large en tokens par minute, mais
> les requêtes se comptent en centaines par jour. Enregistrer les deux clés fait
> disparaître les deux murs.
>
> (Le million de tokens souvent cité pour Gemini est sa **fenêtre de contexte** —
> ce qu'il peut lire en une fois — pas un quota par minute. Les chiffres exacts
> changent selon le modèle et la génération : la page du fournisseur fait foi.)

### Si le téléchargement du modèle échoue (erreur 404)

Cela veut dire que le dépôt HuggingFace a été renommé depuis l'écriture de
l'application. Trois solutions, toutes réalisables depuis le téléphone :

- **Essayer un autre modèle** du catalogue. Il y en a sept.
- **Coller un lien direct** : chercher le modèle sur *huggingface.co* dans le
  navigateur du téléphone, onglet *Files*, appui long sur la flèche de
  téléchargement du fichier `.gguf` → *Copier le lien*, puis dans l'application
  *Réglages → Modèles d'IA → Télécharger depuis un lien*.
- **Télécharger avec le navigateur** puis *Modèles d'IA → Importer un fichier
  .gguf*. Attention : cette méthode occupe temporairement deux fois la taille du
  modèle sur le téléphone.

### Et si vous avez un jour accès à un ordinateur

```bash
git clone https://github.com/ismacous/JobMaker.git
cd JobMaker
./gradlew installDebug        # téléphone branché en USB
```

Il faut alors Android Studio avec le **NDK 27.2.12479018** et **CMake 3.22.1**.
Détails dans [`docs/BUILD.md`](docs/BUILD.md). Ce n'est en aucun cas nécessaire.

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

Cela dépend du moteur choisi, et l'application l'affiche à chaque écran
concerné.

**Mode « sur l'appareil »** — aucune donnée personnelle n'est envoyée nulle
part. L'application ne contacte que HuggingFace, uniquement pour télécharger les
fichiers de modèle, et ne transmet alors rien vous concernant.

**Mode « API gratuite »** — chaque génération envoie au fournisseur choisi le
texte de l'offre et le résumé de votre profil (expériences, formations,
compétences, langues), votre nom compris. Ne sortent **jamais** : votre photo,
vos coordonnées exactes — ajoutées au CV après coup, au moment du rendu — vos
candidatures enregistrées et vos PDF. Rien n'est envoyé à qui que ce soit
d'autre : pas de serveur JobMaker, pas de statistiques, pas de compte.

Dans les deux cas :

- Tout est stocké dans l'espace privé de l'application. Une désinstallation
  efface tout.
- Votre clé d'API est chiffrée par le Keystore matériel du téléphone, exclue des
  sauvegardes, et n'apparaît jamais dans les journaux. Détail complet et limites
  assumées : [`docs/SECURITE.md`](docs/SECURITE.md).
- **Exportez votre profil régulièrement** : *Profil → Sauvegarde du profil →
  Exporter*. C'est votre seule protection en cas de perte du téléphone.

---

## En cas de problème

**« Aucun modèle installé »** → *Réglages → Moteur d'IA* : soit enregistrer une
clé d'API gratuite (deux minutes), soit *Modèles d'IA* → télécharger (2,5 Go).

**« Clé refusée »** → la clé a été révoquée ou mal collée. *Réglages → Moteur
d'IA* → **Supprimer la clé**, puis en créer une nouvelle chez le fournisseur.

**« Le modèle n'existe plus »** → les fournisseurs retirent des modèles
régulièrement. *Réglages → Moteur d'IA → Changer de modèle* interroge l'API et
liste ceux disponibles aujourd'hui.

**« Quota gratuit atteint »** → attendre quelques minutes, changer de
fournisseur, ou repasser sur le moteur embarqué.

**Le téléchargement du modèle échoue (erreur 404)** → voir *Installation → Si le
téléchargement du modèle échoue* plus haut. Trois solutions, toutes faisables
depuis le téléphone.

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

**La compilation GitHub échoue** → onglet *Actions* → ouvrir l'exécution en
rouge → l'étape rouge affiche le message. Voir la section « Si la compilation
native échoue » de [`docs/BUILD.md`](docs/BUILD.md).

**Le téléchargement du modèle s'arrête tout seul** → Android a mis
l'application en veille. Relancez-le : il reprend où il en était. Brancher le
téléphone au chargeur et laisser l'écran allumé évite le problème.

---

## Documentation

- [`docs/BUILD.md`](docs/BUILD.md) — compilation détaillée, options du moteur
  natif, problèmes connus
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — organisation du code et
  décisions techniques
- [`docs/SECURITE.md`](docs/SECURITE.md) — ce qui est fait de la clé d'API, ce
  qui sort du téléphone, et ce que cette architecture ne protège pas

## Tests

Ils tournent automatiquement à chaque compilation GitHub, avant la production de
l'APK : une régression empêche la publication d'une version cassée. Sur un
ordinateur : `./gradlew testDebugUnitTest`.

44 tests couvrent les parties où une régression serait la plus coûteuse : la
construction des requêtes vers les API distantes et la lecture de leur flux, la
récupération du JSON produit par un modèle local, la détection des inventions,
et le rendu des documents.

La compilation vérifie aussi, avant toute autre chose, qu'aucune clé d'API n'a
été commitée (`scripts/verifier-secrets.sh`).
