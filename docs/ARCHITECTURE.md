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
MoteurTexte  (interface)       Candidature (Room)
    │                               │
    ├── MoteurLocal                 ▼
    │     └─ LlmRuntime ──► LlamaBridge (JNI) ──► llama.cpp (C++/NDK)
    │                          HtmlRenderer ──► PdfExporter
    └── MoteurCloud               (gabarit)     (moteur d'impression Android)
          └─ ClientCloud ──► API distante (Groq / Gemini / OpenRouter)
```

L'orchestrateur ne connaît que `MoteurTexte`. `MoteurAvecRepli` enveloppe le
moteur distant et bascule sur le local en cas de panne passagère.

## Organisation du code

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt        récupère et compile llama.cpp
│   └── llama_jni.cpp         pont JNI : charger, tokeniser, générer
├── assets/
│   └── models_catalog.json   catalogue des modèles téléchargeables
└── java/com/jobmaker/
    ├── llm/                  moteurs de génération
    │   ├── MoteurTexte.kt        l'interface que voit l'orchestrateur
    │   ├── ConfigMoteur.kt       mode, fournisseur, modèle, repli
    │   ├── FabriqueMoteur.kt     choisit le moteur selon les réglages
    │   ├── MoteurAvecRepli.kt    distant, avec filet de sécurité local
    │   ├── LlamaBridge.kt        déclarations natives
    │   ├── LlmRuntime.kt         session unique, sérialisation des appels
    │   ├── ModelCatalog.kt       catalogue et rôles d'agents
    │   ├── ModelManager.kt       téléchargement, import, suppression
    │   ├── GenerationParams.kt   réglages d'échantillonnage
    │   ├── local/
    │   │   └── MoteurLocal.kt    GGUF chargé dans le processus
    │   └── cloud/
    │       ├── FournisseurCloud.kt  les trois offres gratuites
    │       ├── RequetesCloud.kt     corps, flux SSE, erreurs (pur, testable)
    │       ├── ClientCloud.kt       appels OkHttp, reprises, redaction
    │       └── MoteurCloud.kt       implémentation distante de MoteurTexte
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
    │   └── prefs/                réglages (DataStore) + CoffreCles (clés d'API)
    ├── render/
    │   ├── CvTemplates.kt        les quatre gabarits (CSS)
    │   ├── HtmlRenderer.kt       contenu → HTML A4
    │   ├── PdfExporter.kt        HTML → PDF
    │   └── DocumentExporter.kt   fichiers, partage, export texte
    └── ui/                   Jetpack Compose
```

## Décisions techniques

### Un moteur de texte derrière une interface, pas un `if` dans l'orchestrateur

Le pipeline a été écrit pour un modèle local et fonctionne aussi bien avec une
API distante : mêmes prompts, mêmes vérifications, mêmes garde-fous contre
l'invention. Il aurait été tentant de brancher l'API par un test au bon endroit
dans `Orchestrator` ; le choix a été d'introduire `MoteurTexte` et de faire
passer les deux implémentations par la même porte.

Ce que cela achète :

- La règle « l'IA n'a pas le droit d'inventer » ne dépend pas du moteur.
  `FactCheck` et la recopie des formations depuis le profil s'appliquent aux
  deux, sans duplication.
- `MoteurAvecRepli` devient possible : un décorateur de 60 lignes qui bascule du
  distant au local en cours de pipeline, sans que l'orchestrateur le sache.
- Les tests portent sur la construction des requêtes et la lecture du flux
  (`RequetesCloud`, fonctions pures), pas sur un assemblage difficile à isoler.

### Ne jamais embarquer de clé, parce que le dépôt est public

Le dépôt est public — c'est ce qui donne droit aux minutes de compilation
gratuites de GitHub Actions. Une clé partagée, livrée avec l'APK, serait donc
publiée deux fois : dans l'historique Git et dans l'APK décompilable.

D'où l'architecture : chacun saisit sa clé, elle est chiffrée par le Keystore
matériel, exclue des sauvegardes, jamais journalisée, et relue au coffre au
début de chaque génération plutôt que conservée en mémoire. Un script vérifie à
chaque compilation qu'aucune clé n'a été commitée. Détail dans
[`SECURITE.md`](SECURITE.md).

### JSON contraint par l'API quand elle sait le faire

En local, le JSON produit par un modèle de 4 milliards de paramètres est
souvent presque valide, d'où `JsonRepair` et la passe de réparation par le
modèle lui-même. Les API savent contraindre la sortie (`response_format` chez
les dialectes OpenAI, `responseMimeType` chez Google) : `GenerationParams`
porte donc un indicateur `sortieJson` que seuls les moteurs distants honorent.
La passe de réparation reste en place — elle ne se déclenche simplement plus.

Un modèle distant qui refuserait ce mode (`400`) fait retenter l'appel sans la
contrainte : mieux vaut un JSON à réparer qu'une génération perdue.

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
