#!/usr/bin/env bash
# scripts/restore-env.sh
#
# Regenera o ficheiro .env a partir do macOS Keychain.
#
# Por que existe: o `.env` está no .gitignore e não vai para o GitHub.
# Sempre que substituis os ficheiros locais a partir do repo (clone novo,
# reset, etc.), o .env desaparece — e sem ele o build injecta o
# placeholder MY_GEMINI_API_KEY, que GeminiRepository rejeita em runtime.
#
# Storage: macOS Keychain
#   service = "nutrimadhvi"
#   account = "GEMINI_API_KEY"
#
# Para mudar a chave (ex.: depois de revogar): correr
#   security add-generic-password -s nutrimadhvi -a GEMINI_API_KEY -w "<NOVA>" -U
#
# Uso:
#   ./scripts/restore-env.sh         # cria/sobrescreve .env
#   ./scripts/restore-env.sh --check # só verifica que o .env existente é válido (sai 0/1)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
SERVICE="nutrimadhvi"
ACCOUNT="GEMINI_API_KEY"

read_keychain() {
  security find-generic-password -s "$SERVICE" -a "$ACCOUNT" -w 2>/dev/null
}

if [[ "${1:-}" == "--check" ]]; then
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "✗ .env não existe" >&2
    exit 1
  fi
  if grep -q "^GEMINI_API_KEY=MY_GEMINI_API_KEY$" "$ENV_FILE"; then
    echo "✗ .env contém o placeholder MY_GEMINI_API_KEY" >&2
    exit 1
  fi
  if ! grep -q "^GEMINI_API_KEY=" "$ENV_FILE"; then
    echo "✗ .env não tem GEMINI_API_KEY" >&2
    exit 1
  fi
  echo "✓ .env parece válido"
  exit 0
fi

KEY="$(read_keychain || true)"
if [[ -z "$KEY" ]]; then
  cat >&2 <<EOF
✗ Não encontrei GEMINI_API_KEY no Keychain.
  Adiciona com:
    security add-generic-password -s $SERVICE -a $ACCOUNT -w "<chave>" -U
EOF
  exit 1
fi

# Escrever o .env (sobrescreve sempre).
umask 077  # 0600 — só o user lê
cat > "$ENV_FILE" <<EOF
# Gerado por scripts/restore-env.sh a partir do macOS Keychain.
# NÃO fazer commit (já está no .gitignore).
# Para rodar a chave: security add-generic-password -s $SERVICE -a $ACCOUNT -w "<NOVA>" -U
GEMINI_API_KEY=$KEY
EOF
chmod 600 "$ENV_FILE"
echo "✓ .env regenerado em $ENV_FILE (perm 600, $(wc -c < "$ENV_FILE") bytes)"
