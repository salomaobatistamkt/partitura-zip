#!/usr/bin/env bash
# Roda o servidor de leitura na SUA rede (Linux/macOS). Precisa de: python3, poppler (pdftoppm).
#   Linux:  sudo apt install python3-venv poppler-utils      macOS:  brew install poppler
# Só aparelhos pareados (com o código que aparece aqui) conseguem usar. Nada é exposto na internet.
set -e
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  python3 -m venv .venv
  .venv/bin/pip install --upgrade pip
  .venv/bin/pip install homr pillow
  .venv/bin/homr --init
fi
export PATH="$PWD/.venv/bin:$PATH"
export DISCOVERY=1      # deixa o app achar este computador sozinho ("Procurar na rede")
export BIND=0.0.0.0
echo "Endereços deste computador (use um destes no app, porta 8080):"
(hostname -I 2>/dev/null || ipconfig getifaddr en0 2>/dev/null) | tr ' ' '\n' | sed '/^$/d;s#^#  http://#;s#$#:8080#'
exec python3 server.py
