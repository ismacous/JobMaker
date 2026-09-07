# Compilation

## Prérequis

| Outil | Version | Où l'installer |
|-------|---------|----------------|
| Android Studio | Ladybug ou plus récent | developer.android.com |
| JDK | 17 (fourni avec Android Studio) | — |
| Android SDK | API 36 | SDK Manager |
| NDK | 27.2.12479018 | SDK Manager → SDK Tools → *NDK (Side by side)* |
| CMake | 3.22.1 | SDK Manager → SDK Tools → *CMake* |

Le NDK et CMake ne sont pas installés par défaut : il faut cocher les cases
« Show Package Details » dans le SDK Manager pour choisir la version exacte.

## Commandes

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

## Signature de release

Le build `debug` suffit pour un usage personnel : il s'installe et fonctionne
sans limite de durée.

Pour produire un APK `release` (plus petit et plus rapide au démarrage), créez
un fichier `keystore.properties` à la racine du projet :

```properties
storeFile=ma-cle.jks
storePassword=...
keyAlias=jobmaker
keyPassword=...
```

puis `./gradlew assembleRelease`. Sans ce fichier, le build release n'est pas
signé et ne s'installera pas.

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
  404 au téléchargement. L'import manuel d'un `.gguf` contourne le problème.
- **Des détails d'interface** — un espacement, une couleur, une marge.

La logique testée (récupération du JSON des modèles, détection des inventions,
rendu HTML des quatre gabarits, sauvegarde/restauration du profil) est, elle,
vérifiée par la suite de tests.
