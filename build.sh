#!/usr/bin/env bash
# 纯 Android SDK 构建：aapt2 编译资源 -> javac -> d8 -> apk -> zipalign -> apksigner
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WS="$(cd "$ROOT/../.." && pwd)"
SDK="$HOME/android-sdk"
BP="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
JDK="/usr/lib/jvm/java-17-openjdk-amd64/bin"
OUT="/tmp/forest-racer-build"
APK="$WS/versions/ForestRacer-v01.01.00.00.apk"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JDK:$PATH"

echo "[build] clean"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res" "$OUT/assets"
cp -r "$ROOT/assets/." "$OUT/assets/"

echo "[build] copy assets"
find "$OUT/assets" -type f | wc -l

echo "[build] aapt2 compile"
"$BP/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "[build] aapt2 link"
"$BP/aapt2" link -o "$OUT/base.apk" -I "$PLAT" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 34 \
  "$OUT/res.zip"

echo "[build] javac"
mkdir -p "$OUT/gen"
find "$ROOT/src" "$OUT/gen" -name '*.java' | sed 's/^/"/;s/$/"/' > "$OUT/sources.txt"
wc -l "$OUT/sources.txt"
"$JDK/javac" -source 8 -target 8 -classpath "$PLAT" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[build] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BP/d8" --lib "$PLAT" --min-api 24 \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "[build] package dex + assets"
# Android 要求 classes.dex 位于 APK 根目录，不能放在子目录
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
rm -f "$OUT/merged.apk"
cp "$OUT/base.apk" "$OUT/merged.apk"
(cd "$OUT" && zip -q merged.apk classes.dex)
(cd "$OUT" && zip -q -r merged.apk assets)

echo "[build] zipalign"
"$BP/zipalign" -f -p 4 "$OUT/merged.apk" "$OUT/aligned.apk"

echo "[build] apksigner"
if [ -f "$ROOT/debug.keystore" ]; then
  KS="$ROOT/debug.keystore"
else
  "$JDK/keytool" -genkeypair -keystore "$ROOT/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
  KS="$ROOT/debug.keystore"
fi
"$BP/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/aligned.apk"

echo "[build] verify"
"$BP/apksigner" verify "$APK"
ls -lh "$APK"
echo "[build] DONE $APK"
