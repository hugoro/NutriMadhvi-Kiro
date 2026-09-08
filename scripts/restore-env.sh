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

read_keychain() {
  security find-generic-password -s "$SERVICE" -a "$1" -w 2>/dev/null
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

KEY="$(read_keychain GEMINI_API_KEY || true)"
if [[ -z "$KEY" ]]; then
  cat >&2 <<EOF
✗ Não encontrei GEMINI_API_KEY no Keychain.
  Adiciona com:
    security add-generic-password -s $SERVICE -a GEMINI_API_KEY -w "<chave>" -U
EOF
  exit 1
fi

# Groq — fallback DORMENTE do ensemble de meal-vision. Opcional: se não
# estiver no Keychain, escrevemos o placeholder (o app corre GEMINI_ONLY).
GROQ_KEY="$(read_keychain GROQ_API_KEY || true)"
GROQ_LINE="GROQ_API_KEY=MY_GROQ_API_KEY"
if [[ -n "$GROQ_KEY" ]]; then
  GROQ_LINE="GROQ_API_KEY=$GROQ_KEY"
fi

# Bitdeer — host alternativo para Qwen3.8-27B (benchmark/failover). Opcional,
# mesmo padrão do Groq: sem chave no Keychain → placeholder.
BITDEER_KEY="$(read_keychain BITDEER_API_KEY || true)"
BITDEER_LINE="BITDEER_API_KEY=MY_BITDEER_API_KEY"
if [[ -n "$BITDEER_KEY" ]]; then
  BITDEER_LINE="BITDEER_API_KEY=$BITDEER_KEY"
fi

# RunInfra — o failover real do Groq na cadeia de chat (Groq → RunInfra →
# Gemini). Opcional, mesmo padrão do Groq/Bitdeer: sem chave no Keychain →
# placeholder MY_RUNINFRA_API_KEY (o RunInfraChatProvider trata o placeholder
# como "não configurado" e o router salta o link em vez de falhar).
RUNINFRA_KEY_VAL="$(read_keychain RUNINFRA_API_KEY || true)"
RUNINFRA_LINE="RUNINFRA_API_KEY=MY_RUNINFRA_API_KEY"
if [[ -n "$RUNINFRA_KEY_VAL" ]]; then
  RUNINFRA_LINE="RUNINFRA_API_KEY=$RUNINFRA_KEY_VAL"
fi

# Escrever o .env (sobrescreve sempre).
umask 077  # 0600 — só o user lê
cat > "$ENV_FILE" <<EOF
# Gerado por scripts/restore-env.sh a partir do macOS Keychain.
# NÃO fazer commit (já está no .gitignore).
# Para rodar a chave Gemini: security add-generic-password -s $SERVICE -a GEMINI_API_KEY -w "<NOVA>" -U
# Para rodar a chave Groq:   security add-generic-password -s $SERVICE -a GROQ_API_KEY -w "<NOVA>" -U
# Para rodar a chave Bitdeer: security add-generic-password -s $SERVICE -a BITDEER_API_KEY -w "<NOVA>" -U
# Para rodar a chave RunInfra: security add-generic-password -s $SERVICE -a RUNINFRA_API_KEY -w "<NOVA>" -U
GEMINI_API_KEY=$KEY
$GROQ_LINE
$BITDEER_LINE
$RUNINFRA_LINE
EOF
chmod 600 "$ENV_FILE"
echo "✓ .env regenerado em $ENV_FILE (perm 600, $(wc -c < "$ENV_FILE") bytes)"
