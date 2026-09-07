# Compilation

## Sans ordinateur : GitHub Actions

`.github/workflows/construire-apk.yml` compile l'application sur les serveurs
de GitHub et publie l'APK en *release*. C'est la voie normale pour ce projet :
elle ne demande qu'un navigateur de téléphone.

Le workflow se déclenche automatiquement à chaque poussée sur `main` ou sur une
branche `claude/**`, et manuellement depuis l'onglet *Actions* → *Construire
l'APK* → *Run workflow*.

Il installe le SDK, le NDK 27.2.12479018 et CMake 3.22.1 sur le runner, lance
les tests unitaires, compile l'APK de debug, puis crée une release taguée
`v<numéro d'exécution>` avec `JobMaker.apk` en pièce jointe. Le lien
`releases/latest` pointe donc toujours vers la dernière version. L'APK est aussi
déposé en artefact de workflow (sous forme de `.zip`, moins pratique sur
téléphone).

Les tests passent **avant** la compilation : une régression empêche la
publication d'une version cassée.

Durée : 15 à 25 minutes, dominée par la compilation de llama.cpp. Les
dépendances Gradle sont mises en cache d'une exécution à l'autre ; le code natif
est recompilé chaque fois (un cache `.cxx` périmé provoque des échecs plus
difficiles à diagnostiquer que le temps qu'il fait gagner).

## Pourquoi une clé de signature est versionnée

`debug.keystore` est dans le dépôt, avec son mot de passe en clair dans
`app/build.gradle.kts`. C'est délibéré.

Android refuse d'installer une application par-dessus une autre signée avec une
clé différente. Sans clé stable, chaque compilation GitHub produirait une
signature différente, et chaque mise à jour imposerait de désinstaller
l'application — donc de perdre son profil et ses candidatures.

Le risque réel est nul ici : l'application n'est publiée sur aucun magasin, et
c'est exactement le rôle du `debug.keystore` que tout projet Android partage
entre ses développeurs (Google en documente publiquement le mot de passe). Pour
une véritable diffusion, il faudrait une clé privée gardée hors du dépôt et
injectée par un secret GitHub.

## Avec un ordinateur

### Prérequis

| Outil | Version | Où l'installer |
|-------|---------|----------------|
| Android Studio | Ladybug ou plus récent | developer.android.com |
| JDK | 17 (fourni avec Android Studio) | — |
| Android SDK | API 36 | SDK Manager |
| NDK | 27.2.12479018 | SDK Manager → SDK Tools → *NDK (Side by side)* |
| CMake | 3.22.1 | SDK Manager → SDK Tools → *CMake* |

Le NDK et CMake ne sont pas installés par défaut : il faut cocher les cases
« Show Package Details » dans le SDK Manager pour choisir la version exacte.

### Commandes

```bash
./gradlew assembleDebug        # produit app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # compile et installe sur le téléphone branché
./gradlew testDebugUnitTest    # tests unitaires (JVM, pas besoin de téléphone)
```

## Le moteur d'inférence

L'application embarque [llama.cpp](https://github.com/ggml-org/llama.cpp),
compilé en même temps qu'elle. Le pont JNI est dans
`app/src/main/cpp/llama_jni.cpp`, la recette de compilation dans
`app/src/main/cpp/CMakeLists.txt`.

llama.cpp n'est pas un sous-module git : `CMakeLists.txt` télécharge l'archive
d'une version figée au premier build et la met en cache. La version est
paramétrée dans `gradle.properties` :

```properties
# Version de llama.cpp compilée avec l'application.
jobmaker.llamaTag=b6100

# Jeu d'instructions ARM. Le Snapdragon 8 Elite du S25 Ultra gère
# dotprod + i8mm + fp16, ce qui accélère beaucoup les calculs quantifiés.
jobmaker.armArch=armv8.2-a+dotprod+i8mm+fp16

# Backend GPU Adreno (OpenCL). Expérimental, compilation plus fragile.
jobmaker.opencl=false

# Compiler sans la partie C++ : l'interface fonctionne, la génération non.
# Utile pour itérer vite sur l'écran Profil.
jobmaker.skipNative=false
```

### Si la compilation native échoue

Le code JNI est écrit pour l'API de la version `jobmaker.llamaTag`. llama.cpp
change son API régulièrement ; changer ce tag peut casser la compilation.

1. **Remettre le tag d'origine** (`b6100`) si vous l'aviez modifié.
2. **Nettoyer le cache CMake** : supprimer `app/.cxx/` et `app/build/`, puis
   recompiler.
3. **Vérifier la version du NDK** : `ndkVersion` dans `app/build.gradle.kts`
   doit correspondre à une version réellement installée. Android Studio propose
   normalement de l'installer tout seul.
4. **Erreur réseau au moment du `FetchContent`** : le téléchargement de
   llama.cpp a échoué. Relancer ; l'archive est mise en cache une fois
   récupérée.

Si vous devez passer à une version plus récente de llama.cpp et que le JNI ne
compile plus, les fonctions concernées sont concentrées dans
`llama_jni.cpp` — les plus susceptibles d'avoir changé sont
`llama_model_load_from_file`, `llama_init_from_model`, `llama_memory_clear`,
`llama_sampler_init_penalties` et `llama_chat_apply_template`.

### Backend GPU (optionnel, expérimental)

`jobmaker.opencl=true` active le backend OpenCL de llama.cpp, qui sait utiliser
le GPU Adreno du Snapdragon. Cela peut accélérer sensiblement la génération,
mais la compilation est plus fragile (en-têtes OpenCL et ICD loader récupérés
en plus). **Ne l'activez qu'après avoir réussi une compilation CPU** et gardez
de quoi revenir en arrière. Une fois activé, le curseur *Couches déportées sur
le GPU* des réglages devient utile ; sinon il reste sans effet.

## Build de release

Le build `debug` est celui que produit GitHub Actions et il suffit pour un usage
personnel : il s'installe et fonctionne sans limite de durée. Il n'est pas
minifié, ce qui le rend plus gros mais évite tout risque que R8 supprime par
erreur du code atteint par réflexion — un risque qu'on ne peut pas prendre à la
légère sur une application qu'on ne peut pas déboguer facilement.

Pour produire un APK `release` avec votre propre clé, créez un fichier
`keystore.properties` à la racine du projet :

```properties
storeFile=ma-cle.jks
storePassword=...
keyAlias=jobmaker
keyPassword=...
```

puis `./gradlew assembleRelease`. Sans ce fichier, le build release retombe sur
la clé de debug versionnée.

## Points non vérifiés

Ce projet a été écrit et relu intégralement, et sa partie logique est couverte
par des tests qui passent. En revanche, **il n'a pas pu être compilé pour
Android ni exécuté sur un appareil** dans l'environnement de développement
utilisé (le dépôt Maven de Google et HuggingFace y étaient inaccessibles).

Concrètement, attendez-vous à devoir éventuellement corriger :

- **La compilation native** — dépend de la version exacte de llama.cpp
  récupérée. Voir plus haut.
- **Les noms de dépôts HuggingFace** du catalogue
  (`app/src/main/assets/models_catalog.json`). L'application interroge l'API de
  HuggingFace pour trouver le bon nom de fichier, donc un fichier renommé n'est
  pas un problème ; en revanche un dépôt renommé ou supprimé donnera une erreur
  404 au téléchargement. Deux contournements existent dans l'application, tous
  deux utilisables depuis un téléphone seul : *Télécharger depuis un lien* et
  *Importer un fichier .gguf*.
- **Des détails d'interface** — un espacement, une couleur, une marge.

La logique testée (récupération du JSON des modèles, détection des inventions,
rendu HTML des quatre gabarits, sauvegarde/restauration du profil) est, elle,
vérifiée par la suite de tests.
