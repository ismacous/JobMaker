# Architecture

## Vue d'ensemble

```
Offre collée
    │
    ▼
┌─────────────────────────────────────────────────┐
│ Orchestrator                                    │
│   1. Analyste  → JobAnalysis                    │
│   2. Stratège  → Strategy                       │
│   3. Rédacteur CV     → CvContent               │
│   4. Rédacteur lettre → LetterContent           │
│   5. Relecteur → Review    (+ FactCheck, sans IA)│
│   6. Correcteur → CvContent / LetterContent      │
└─────────────────────────────────────────────────┘
    │                              │
    ▼                              ▼
LlmRuntime ──► LlamaBridge     Candidature (Room)
  (Kotlin)       (JNI)              │
                   │                ▼
                   ▼          HtmlRenderer ──► PdfExporter
              llama.cpp          (gabarit)     (moteur d'impression Android)
               (C++/NDK)
```

## Organisation du code

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt        récupère et compile llama.cpp
│   └── llama_jni.cpp         pont JNI : charger, tokeniser, générer
├── assets/
│   └── models_catalog.json   catalogue des modèles téléchargeables
└── java/com/jobmaker/
    ├── llm/                  moteur d'inférence
    │   ├── LlamaBridge.kt        déclarations natives
    │   ├── LlmRuntime.kt         session unique, sérialisation des appels
    │   ├── ModelCatalog.kt       catalogue et rôles d'agents
    │   ├── ModelManager.kt       téléchargement, import, suppression
    │   └── GenerationParams.kt   réglages d'échantillonnage
    ├── agents/               la couche « intelligence »
    │   ├── Prompts.kt            toutes les consignes
    │   ├── Orchestrator.kt       enchaînement des étapes
    │   ├── LlmJson.kt            appel JSON avec réparation
    │   ├── JsonRepair.kt         récupération du JSON sale
    │   ├── ProfileDigest.kt      profil → texte pour le modèle
    │   └── FactCheck.kt          vérifications sans IA
    ├── data/
    │   ├── model/                Profile, Candidature, sorties d'agents
    │   ├── db/                   Room
    │   ├── repo/                 dépôts
    │   └── prefs/                réglages (DataStore)
    ├── render/
    │   ├── CvTemplates.kt        les quatre gabarits (CSS)
    │   ├── HtmlRenderer.kt       contenu → HTML A4
    │   ├── PdfExporter.kt        HTML → PDF
    │   └── DocumentExporter.kt   fichiers, partage, export texte
    └── ui/                   Jetpack Compose
```

## Décisions techniques

### llama.cpp plutôt que MLC-LLM

MLC-LLM est un excellent moteur, mais il demande de compiler chaque modèle avec
la chaîne TVM avant de pouvoir l'utiliser : chaque modèle devient un artefact à
produire soi-même, et le catalogue de modèles disponibles est étroit.

llama.cpp lit le format **GGUF**, qui est le format de diffusion de fait sur
HuggingFace. N'importe quel modèle publié en GGUF fonctionne, y compris ceux
sortis après l'écriture de l'application, et l'utilisateur peut en importer un
lui-même. C'est cette liberté qui a décidé du choix, à performances comparables.

### Un seul modèle en mémoire à la fois

Un modèle 4B quantifié occupe environ 2,5 Go de mémoire vive pendant qu'il
travaille. Deux modèles en parallèle sur un téléphone font tuer le processus par
le gestionnaire de mémoire d'Android — même avec 12 Go, parce que la limite
s'applique au processus, pas à l'appareil.

`LlmRuntime` ne détient donc qu'une session vivante. Affecter des modèles
différents aux étapes reste possible : l'orchestrateur décharge et recharge
entre les étapes, ce qui coûte quelques secondes grâce au `mmap`. Le pipeline
est séquentiel pour la même raison — il n'y a rien à paralléliser.

### JSON structuré plutôt que texte libre

Chaque agent produit du JSON, pas du texte mis en forme. Deux conséquences :

- **La mise en page est indépendante du modèle.** Changer de gabarit ou de
  couleur ne demande aucune régénération, et les quatre gabarits rendent le même
  contenu.
- **Le contenu est modifiable champ par champ.** L'éditeur manipule des
  structures, pas des chaînes à parser.

Le prix à payer est qu'un petit modèle produit du JSON imparfait. D'où
`JsonRepair`, qui retire les blocs de raisonnement, les balises de code et la
prose d'accompagnement, referme un JSON coupé par la limite de tokens, et
corrige les guillemets typographiques. Ce n'est qu'en dernier recours que
`LlmJson` redemande au modèle de corriger sa propre sortie — et sur quelques
dizaines de tokens, pas sur toute la génération.

### Ce qui fait qu'une génération est longue, et ce qui la borne

Le temps d'une génération se décompose en deux phases que rien ne permet de
confondre : la **lecture du prompt**, où le modèle avale les consignes, l'offre
et le profil sans rien écrire, et l'**écriture**, où il produit un token à la
fois. Sur un téléphone, la première va vite (calcul par lots) et la seconde est
lente (limitée par la bande passante mémoire). Une génération qui dure vient
donc presque toujours d'un excès de tokens écrits, pas d'un excès de texte lu.

Quatre mécanismes bornent cette écriture.

**Arrêt dès que le JSON se referme.** `DetecteurJsonComplet` suit la profondeur
des accolades au fil des tokens, en ignorant celles qui sont dans une chaîne et
celles d'un éventuel bloc `<think>`. Dès que l'objet de premier niveau se
referme, la génération s'arrête. Sans cela, un petit modèle répond juste, puis
continue — remerciements, second objet, commentaires — jusqu'à épuiser son
budget. C'est le garde-fou qui compte le plus : le jeton de fin, lui, n'est pas
toujours émis.

**Un budget de tokens dimensionné sur la sortie réelle.** Une lettre de 250 à
330 mots pèse 400 à 550 tokens ; un CV d'une page en JSON, 700 à 900. Les
plafonds d'`Orchestrator` sont réglés là-dessus. Un budget large n'est pas
neutre : il est *réservé* dans la fenêtre de contexte, donc retiré au prompt.
`nativeBeginGenerate` refuse de démarrer quand prompt + budget dépasse le
contexte — un budget généreux « au cas où » faisait échouer l'étape de rédaction
avant le premier mot.

**Un profil dimensionné sur l'étape la plus lourde.** `digestAdapte` calcule ce
que les consignes système, l'offre et le budget d'écriture laissent réellement,
et bascule sur un profil résumé si le profil complet n'y tient pas. Une offre
collée avec la page entière du site est tronquée, avec un avertissement.

**Pas de pénalité de répétition sur les étapes qui structurent.** Les tokens les
plus répétés d'une réponse JSON sont ceux qui la rendent valide : guillemets,
deux-points, virgules, accolades, noms de champs. Les pénaliser pousse le modèle
à s'en écarter, donc à produire du JSON cassé — qu'il faut ensuite faire réparer
par un second appel complet, c'est-à-dire payer l'étape deux fois. Les étapes de
rédaction gardent une pénalité faible et une fenêtre courte, pour empêcher une
phrase de tourner en boucle, pas pour diversifier le vocabulaire : c'est la
graine, tirée au hasard à chaque appel, qui change les formulations.

### Le fil principal ne doit rien avoir à faire pendant la génération

Le texte produit ne remonte pas token par token. Il est regroupé et envoyé au
plus huit fois par seconde, et la notification de progression n'est redessinée
qu'une fois par seconde.

Ce n'est pas du confort d'affichage. Chaque remontée déclenche une mise à jour
d'état, une recomposition Compose et une transaction vers le serveur système ;
au-delà de cinq notifications par seconde, Android les jette tout en faisant
payer le travail. Pendant ce temps, les threads de calcul de ggml se
synchronisent à chaque couche du modèle : le plus lent impose son rythme à tous,
et chaque préemption d'un seul d'entre eux se paie sur le token entier. C'était
la principale différence entre le banc de mesure — qui ne remonte rien et
trouvait le moteur rapide — et une vraie génération.

### Chaque étape rend ses chiffres

`TraceAppel` enregistre, pour chaque appel au modèle : tokens lus et durée,
tokens écrits et durée, budget accordé, et **pourquoi** la génération s'est
arrêtée (jeton de fin, JSON refermé, budget épuisé, contexte plein). L'écran de
génération en fait un bilan copiable.

Une lenteur ne se corrige pas sur une impression. Ces quatre lignes disent
immédiatement laquelle des deux phases coûte, si le modèle a su s'arrêter ou
s'il a rempli son budget, et si la vitesse s'effondre entre la première et la
dernière étape — signe que le téléphone chauffe et se bride.

### Identité injectée au rendu

`HtmlRenderer` lit le nom, le téléphone, l'adresse, l'email et les liens
**directement dans le profil**. Le schéma JSON du rédacteur de CV ne contient
aucun de ces champs. Ce n'est pas de la prudence : c'est la seule façon
d'obtenir une garantie plutôt qu'une promesse. Un modèle qui n'a pas de champ où
écrire un numéro de téléphone ne peut pas en inventer un.

### Vérifications sans IA

`FactCheck` compare le CV produit au profil par simple comparaison de chaînes :

- les employeurs et établissements du CV doivent correspondre à une organisation
  réellement déclarée dans le profil (avec une tolérance pour les reformulations
  du type « Carrefour Market » quand le profil dit « Carrefour ») ;
- les chiffres du CV doivent se retrouver dans le profil (les années et les
  nombres à un chiffre sont ignorés, trop de faux positifs) ;
- la couverture des mots-clés de l'offre est mesurée et affichée.

Un relecteur IA rate des choses. Ces contrôles-là, non : ils ne font que
comparer du texte. Ce sont eux qui alimentent la passe de correction.

### PDF par le moteur d'impression d'Android

`PdfExporter` charge le HTML dans un `WebView` hors écran, puis confie son
`PrintDocumentAdapter` à `PrintManager`. C'est le même moteur qui produit
l'aperçu, donc l'aperçu est fidèle, et le PDF contient du **texte
sélectionnable**.

Ce dernier point n'est pas cosmétique : un PDF fabriqué à partir d'une capture
d'écran est une image, illisible pour les logiciels de tri de candidatures, et
donc écarté avant même d'atteindre un lecteur humain.

Pourquoi `PrintManager` plutôt qu'un appel direct à `onLayout`/`onWrite`, qui
aurait permis d'écrire le fichier sans aucune interaction : les classes de
rappel de ces méthodes (`LayoutResultCallback`, `WriteResultCallback`) ont des
constructeurs *package-private* dans `android.print` et ne peuvent donc pas
être sous-classées depuis Kotlin — le compilateur refuse. `PrintManager` fait
le même travail en interne et c'est l'API que le système expose officiellement.
La contrepartie est visible pour l'utilisateur : la boîte de dialogue
d'impression s'ouvre et il choisit « Enregistrer au format PDF » puis
l'emplacement. L'interface le dit explicitement sous le bouton.

### Profil stocké en un seul document JSON

Le profil est une ligne unique en base, contenant du JSON. Il n'y a qu'un
utilisateur, et le JSON est de toute façon le format dans lequel les données
partent vers le modèle. Une dizaine de tables relationnelles aurait ajouté
beaucoup de code pour aucun bénéfice, et rendu l'export de sauvegarde plus
compliqué qu'il ne l'est.
