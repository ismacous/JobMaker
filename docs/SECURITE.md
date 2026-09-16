# Sécurité et confidentialité des clés d'API

Depuis que JobMaker sait déléguer la rédaction à une API distante, l'application
manipule un secret : votre clé d'API. Ce document dit exactement ce qui en est
fait, et pourquoi chaque choix a été fait ainsi.

---

## Le point de départ : ce dépôt est public

Le dépôt GitHub est public, et c'est volontaire — c'est ce qui donne droit aux
minutes de compilation gratuites de GitHub Actions, qui permettent de fabriquer
l'APK sans ordinateur.

La conséquence est brutale et sans exception : **tout ce qui est commité est
public pour toujours**. Effacer un fichier ne change rien, l'historique Git le
conserve. Et ce n'est pas théorique : des robots moissonnent les commits publics
en continu et une clé y est trouvée en quelques minutes.

Corollaire : **tout ce qui est dans l'APK est public aussi**. L'APK est compilé
publiquement, publié en *release*, et un APK se décompile en quelques secondes.
Une clé « cachée » dans le code, dans `BuildConfig`, dans les ressources ou dans
une bibliothèque native est une clé publiée.

**Il n'existe donc aucun moyen de livrer une clé partagée avec l'application.**
Ce n'est pas une limite de l'implémentation, c'est la nature du problème.

D'où l'architecture retenue : **chacun utilise sa propre clé, saisie sur son
téléphone, et elle n'est jamais commitée ni empaquetée.**

---

## Ce qui est mis en place

### 1. La clé n'existe que sur le téléphone

- Elle est saisie dans *Réglages → Moteur d'IA*, dans un champ masqué.
- Elle n'apparaît dans aucun fichier du dépôt, aucun `buildConfigField`, aucune
  ressource, aucun secret GitHub Actions.
- Il n'y a pas de serveur JobMaker, donc pas de clé côté serveur, pas de compte,
  pas de télémétrie.

### 2. Chiffrement par le matériel (`data/prefs/CoffreCles.kt`)

- Une clé AES-256 est générée **dans le Keystore Android**, donc dans l'élément
  sécurisé du téléphone (StrongBox quand la puce l'accepte, sinon le TEE).
- Cette clé de chiffrement **ne peut pas sortir** du matériel : l'application
  peut seulement demander au système de chiffrer ou déchiffrer.
- Seul le résultat chiffré (AES-GCM, vecteur d'initialisation en tête) est écrit
  sur le disque, dans un fichier dédié : `datastore/jobmaker_secrets.preferences_pb`.
- Ce fichier est **exclu des sauvegardes** Android (`backup_rules.xml`,
  `data_extraction_rules.xml`). Restauré sur un autre téléphone, il serait de
  toute façon illisible : la clé matérielle n'a pas suivi.

### 3. Jamais dans les journaux

`ClientCloud` n'installe aucun intercepteur de journalisation et ne journalise
ni en-tête, ni corps de requête : uniquement l'hôte, le code HTTP et la durée.
Les messages d'erreur renvoyés par le fournisseur sont filtrés pour en retirer
la clé avant affichage. Les journaux Android sont lisibles par l'utilisateur et
finissent parfois dans un rapport de bug ; une clé qui y passe est une clé à
révoquer.

### 4. Verrouillage du réseau

- `network_security_config.xml` interdit **tout trafic en clair** :
  `cleartextTrafficPermitted="false"`, doublé de
  `android:usesCleartextTraffic="false"` dans le manifeste.
- Seules les autorités de certification **du système** sont acceptées, pas
  celles installées par l'utilisateur : un certificat ajouté sur le téléphone ne
  permet pas d'intercepter les requêtes, donc ni le profil ni la clé.
- `ClientCloud.verifierUrl` refuse toute URL qui ne soit pas du HTTPS sur l'hôte
  exact du fournisseur choisi.
- La clé de Google part en en-tête `x-goog-api-key` et non dans l'URL : elle ne
  se retrouve ainsi ni dans les journaux de proxy, ni dans un `Referer`.

### 5. Durée de vie en mémoire réduite

La clé n'est pas injectée une fois pour toutes dans un objet à longue durée de
vie. `FabriqueMoteur` la relit au coffre **au début de chaque génération**, et le
champ de saisie est vidé dès l'enregistrement. Changer de clé prend donc effet
immédiatement.

### 6. Garde-fou automatique à chaque compilation

`scripts/verifier-secrets.sh` inspecte les fichiers versionnés à la recherche de
chaînes ressemblant à une clé (`AIza…`, `gsk_…`, `sk-or-v1-…`, `hf_…`, `sk-…`)
et de fichiers qui n'ont rien à faire dans le dépôt (`local.properties`,
`keystore.properties`, `.env`). Le script tourne **avant** la compilation dans
GitHub Actions : une clé commitée fait échouer la construction au lieu de passer
inaperçue.

Pour le lancer à la main :

```bash
./scripts/verifier-secrets.sh
```

---

## Ce qui sort du téléphone, précisément

En mode **API gratuite**, chaque génération envoie au fournisseur choisi :

- le texte de l'offre que vous avez collée ;
- le résumé de votre profil : expériences, formations, compétences, langues,
  certifications — **votre nom y figure**.

Ne sortent **jamais**, quel que soit le mode :

- votre photo ;
- vos coordonnées exactes (téléphone, adresse postale, e-mail) : elles sont
  ajoutées au CV **après** la génération, au moment du rendu, sur l'appareil ;
- vos candidatures enregistrées et vos PDF.

En mode **sur l'appareil**, rien ne sort du tout. La seule connexion réseau
restante est le téléchargement des modèles.

---

## Ce que les fournisseurs font de ces données

À vérifier avant de choisir — c'est affiché dans l'application, sur l'écran
*Moteur d'IA* :

| Fournisseur | Offre gratuite | Données |
|---|---|---|
| **Groq** | Généreux en requêtes/jour, serré en tokens/minute | Annonce ne pas entraîner ses modèles sur le contenu envoyé par l'API. |
| **Google Gemini** | L'inverse : large en tokens/minute, centaines de requêtes/jour | **Sur l'offre gratuite**, Google se réserve le droit de faire relire les échanges par des humains et de s'en servir pour améliorer ses modèles. |
| **OpenRouter** | Variable selon le modèle | Dépend du modèle ; les modèles `:free` sont souvent fournis en échange de l'usage des données. |

Les chiffres exacts changent selon le modèle et la génération, plusieurs fois par
an : la page du fournisseur fait foi. L'application ne les code pas en dur — elle
lit l'attente que l'API lui indique (en-tête `Retry-After` chez Groq, `retryDelay`
dans le corps de l'erreur chez Google) et s'y conforme.

C'est la raison pour laquelle **Groq est le choix par défaut**, et pour laquelle
le mode « sur l'appareil » reste disponible sans condition.

### Plusieurs clés en même temps

Le coffre stocke **une clé par fournisseur**, chacune chiffrée séparément :
enregistrer une clé Gemini n'efface pas celle de Groq. Quand un fournisseur
sature, l'application passe au suivant dont une clé est enregistrée, et ne
retombe sur le modèle du téléphone qu'en dernier recours.

Conséquence à avoir en tête : **chaque fournisseur de la chaîne peut recevoir
vos données**, selon les règles du tableau ci-dessus. Une génération commencée
chez Groq et terminée chez Gemini aura envoyé votre profil aux deux. L'écran
*Moteur d'IA* affiche la chaîne exacte, dans l'ordre, et l'enchaînement se
désactive d'un interrupteur si vous préférez un seul destinataire.

---

## Ce que cette architecture ne protège pas

Il faut être honnête sur les limites :

- **Un téléphone déverrouillé et compromis pendant que l'application tourne.**
  Aucun stockage local ne résiste à cela ; le Keystore complique l'extraction du
  secret, il ne la rend pas impossible à un attaquant disposant de root et d'un
  accès en direct.
- **Le fournisseur lui-même.** Une fois l'offre et le profil envoyés, ils sont
  chez lui, soumis à ses conditions. C'est le compromis que l'on accepte en
  choisissant ce mode, et c'est pour cela qu'il est affiché noir sur blanc dans
  l'application plutôt que caché derrière un réglage par défaut.

---

## Si une clé a fuité

Dans cet ordre :

1. **La révoquer chez le fournisseur** (console Groq, Google AI Studio,
   OpenRouter). C'est la seule action qui compte réellement.
2. En créer une nouvelle et la saisir dans l'application.
3. Seulement ensuite, nettoyer le dépôt si elle y était — en sachant que cela ne
   « dé-publie » rien.

Chez Google, pensez aussi à **restreindre la clé** dans la console (restriction
par API) : une clé restreinte à l'API Generative Language ne sert à rien
ailleurs.
