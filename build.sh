#!/usr/bin/env bash
#
# Build FileFlow.apk.
#
# Requires JDK 17+ and an Android SDK with:
#   platforms/android-34
#   build-tools/34.0.0
#
# Usage:
#   ANDROID_HOME=/path/to/sdk ./build.sh
#
set -e

: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK path}"
SDK="$ANDROID_HOME"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"

if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

P="$(cd "$(dirname "$0")" && pwd)"
B="$P/build"
OUT="$P/FileFlow.apk"

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/res"

echo "[1/6] aapt2 compile"
find "$P/res" -type f | while read -r f; do
  "$BT/aapt2" compile -o "$B/res" "$f"
done

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o "$B/app.unsigned.apk" \
  -I "$AJ" \
  --manifest "$P/AndroidManifest.xml" \
  --java "$B/gen" \
  -A "$P/assets" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  "$B"/res/*.flat

echo "[3/6] javac"
R_JAVA="$(find "$B/gen" -name 'R.java')"
set +e
javac -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$B/classes" \
  "$R_JAVA" $(find "$P/src" -name '*.java') > "$B/javac.log" 2>&1
JC=$?
set -e
if [ $JC -ne 0 ]; then echo "javac failed:"; cat "$B/javac.log"; exit 1; fi

echo "[4/6] d8"
"$BT/d8" --release --lib "$AJ" --min-api 24 --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

echo "[5/6] package + align"
cp "$B/app.unsigned.apk" "$B/app.apk"
(cd "$B/dex" && zip -q "$B/app.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$B/app.apk" "$B/aligned.apk"

echo "[6/6] sign"
KS="$P/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=FileFlow Debug, O=FileFlow, C=IN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT" "$B/aligned.apk"

echo "Built: $OUT"
