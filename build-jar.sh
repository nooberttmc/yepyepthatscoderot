#!/usr/bin/env sh
# Builds a double-clickable voidbeat.jar (keep the songs/ folder next to it for custom tracks).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -nowarn -d out src/voidbeat/*.java
jar --create --file voidbeat.jar --main-class voidbeat.Main -C out .
echo "Built voidbeat.jar  ->  java -jar voidbeat.jar"
