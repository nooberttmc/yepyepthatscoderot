#!/usr/bin/env sh
# Compiles and runs VOIDBEAT. Requires a JDK 17 or newer.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -nowarn -d out src/voidbeat/*.java
exec java -Xmx1g -cp out voidbeat.Main "$@"
