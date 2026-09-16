#!/usr/bin/env bash
#
# Garde-fou contre la publication d'une cle d'API dans un depot public.
#
# Le depot de JobMaker est public -- c'est ce qui donne droit aux minutes de
# compilation gratuites de GitHub Actions. Tout ce qui y est commite est
# definitivement lisible par tout le monde, y compris apres suppression :
# l'historique Git garde le fichier, et les robots qui moissonnent GitHub
# trouvent une cle en quelques minutes.
#
# L'application est concue pour qu'aucune cle n'ait a etre commitee : elle est
# saisie sur le telephone et chiffree par le materiel. Ce script verifie que
# personne n'a pris de raccourci, et fait echouer la compilation le cas echeant.
#
# Usage : scripts/verifier-secrets.sh
set -uo pipefail

cd "$(dirname "$0")/.."

# Les motifs sont assembles morceau par morceau pour que le script ne se
# declenche pas sur lui-meme.
declare -a MOTIFS=(
  "AIza""[0-9A-Za-z_-]{30,}"             # Google AI Studio / Gemini
  "gsk_""[0-9A-Za-z]{40,}"               # Groq
  "sk-or-""v1-[0-9a-f]{48,}"             # OpenRouter
  "hf_""[0-9A-Za-z]{30,}"                # HuggingFace
  "sk-""[A-Za-z0-9]{44,}"                # OpenAI et compatibles
)

echec=0
for motif in "${MOTIFS[@]}"; do
  # --cached : on inspecte ce qui est reellement suivi par Git, pas les
  # fichiers locaux ignores (local.properties, keystore.properties...).
  trouve=$(git grep -n -I -E "$motif" -- \
    ':!scripts/verifier-secrets.sh' \
    ':!*.gguf' || true)
  if [ -n "$trouve" ]; then
    echo "ERREUR : une chaine ressemblant a une cle d'API est versionnee." >&2
    echo "$trouve" >&2
    echec=1
  fi
done

# Les fichiers qui n'ont rien a faire dans le depot, meme vides.
for fichier in local.properties keystore.properties .env secrets.properties; do
  if git ls-files --error-unmatch "$fichier" >/dev/null 2>&1; then
    echo "ERREUR : $fichier est versionne. Le retirer : git rm --cached $fichier" >&2
    echec=1
  fi
done

if [ "$echec" -ne 0 ]; then
  echo >&2
  echo "Si une cle a bien ete publiee : la revoquer chez le fournisseur AVANT" >&2
  echo "toute autre chose. La retirer du depot ne suffit pas, l'historique la" >&2
  echo "conserve et elle a deja ete moissonnee." >&2
  exit 1
fi

echo "Aucun secret detecte dans les fichiers versionnes."
