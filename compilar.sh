#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
mkdir -p out
# Os caminhos sob src não têm espaços; a pasta do projeto pode ter.
find src -name '*.java' -print > out/fontes.txt
"$TP2_JAVAC" -encoding UTF-8 --release 11 -d out @out/fontes.txt
echo "Compilação concluída (compatível com Java 11+)."
