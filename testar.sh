#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
./compilar.sh
if [[ "${1:-}" == "--base-100k" ]]; then
  exec "$TP2_JAVA" -Dfile.encoding=UTF-8 -Xmx1g -cp out TesteTP2 --base-100k
fi
for teste in TesteArvoreBMais TesteHashEstendido TesteListaInvertida TesteTP2 TesteRecuperacaoTP2 TesteRevisaoTP2 TesteTP1; do
  "$TP2_JAVA" -Dfile.encoding=UTF-8 -Xmx1g -cp out "$teste"
done
