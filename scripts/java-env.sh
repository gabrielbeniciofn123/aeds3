#!/usr/bin/env bash
# Localiza um JDK. O portátil é apenas uma conveniência deste ambiente local.
if [[ -x ".tools/jdk/Contents/Home/bin/javac" ]]; then
  TP2_JAVA="$(pwd)/.tools/jdk/Contents/Home/bin/java"
  TP2_JAVAC="$(pwd)/.tools/jdk/Contents/Home/bin/javac"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  TP2_JAVA="$JAVA_HOME/bin/java"
  TP2_JAVAC="$JAVA_HOME/bin/javac"
else
  TP2_JAVA=java
  TP2_JAVAC=javac
fi
if ! "$TP2_JAVAC" -version >/dev/null 2>&1; then
  echo "É necessário um JDK 11 ou superior. Instale um JDK e execute novamente." >&2
  exit 1
fi
