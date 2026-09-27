#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
./compilar.sh
exec "$TP2_JAVA" -Dfile.encoding=UTF-8 -Xmx1g -cp out DemonstracaoTP2
