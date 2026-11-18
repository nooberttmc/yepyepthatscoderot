#!/usr/bin/env sh
# Builds dist/VOIDBEAT-windows.zip: VOIDBEAT.exe + the game + a bundled Java runtime,
# so players on Windows just unzip and double-click (no Java install needed).
# Needs: a JDK 17+, python3, curl, unzip, zip, and mingw-w64 (x86_64-w64-mingw32-gcc / windres).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/windows"
OUT="$WORK/VOIDBEAT"
rm -rf "$WORK" && mkdir -p "$OUT/app" "$OUT/songs" "$ROOT/dist"

echo "==> compiling game"
mkdir -p "$WORK/classes"
javac -nowarn --release 17 -d "$WORK/classes" "$ROOT"/src/voidbeat/*.java
jar --create --file "$OUT/app/voidbeat.jar" --main-class voidbeat.Main -C "$WORK/classes" .

echo "==> fetching Windows Java runtime (OpenJDK build packaged by jdk4py on PyPI)"
URL=$(curl -fsS https://pypi.org/pypi/jdk4py/json | python3 -c "
import json,sys; d=json.load(sys.stdin); v=d['info']['version']
print([f['url'] for f in d['releases'][v] if f['filename'].endswith('win_amd64.whl')][0])")
curl -fsS -o "$WORK/jre.whl" "$URL"
unzip -q "$WORK/jre.whl" 'jdk4py/java-runtime/*' -d "$WORK/whl"
mv "$WORK/whl/jdk4py/java-runtime" "$OUT/runtime"
rm -rf "$OUT/runtime/include"

echo "==> building VOIDBEAT.exe"
cd "$ROOT/packaging/windows"
x86_64-w64-mingw32-windres launcher.rc -O coff -o "$WORK/launcher.res"
x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows -o "$OUT/VOIDBEAT.exe" launcher.c "$WORK/launcher.res"

cp "$ROOT/songs/README.txt" "$OUT/songs/README.txt"
cat > "$OUT/README.txt" <<'TXT'
VOIDBEAT - 4-key rhythm game

  Double-click VOIDBEAT.exe to play. Controls: D F J K.

  Keep this folder together: VOIDBEAT.exe needs the "app" and "runtime" folders.
  Add your own music (WAV) to the "songs" folder, then press F5 in song select.
  Settings and best scores are saved in %USERPROFILE%\.voidbeat

  If Windows SmartScreen warns about an unrecognized app, click "More info" ->
  "Run anyway" (the exe isn't code-signed).

  Bundled Java runtime: OpenJDK (GPLv2 with Classpath Exception), see runtime\legal.
TXT

echo "==> zipping"
rm -f "$ROOT/dist/VOIDBEAT-windows.zip"
cd "$WORK" && zip -qr9 "$ROOT/dist/VOIDBEAT-windows.zip" VOIDBEAT
ls -lh "$ROOT/dist/VOIDBEAT-windows.zip"
