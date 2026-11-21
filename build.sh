#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Set ANDROID_JAR to the Android API 35 platform jar}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS to Android Build Tools 35.0.0}"
: "${XPOSED_JAR:?Set XPOSED_JAR to Xposed API 82}"
: "${ECJ_JAR:?Set ECJ_JAR to Eclipse ECJ 3.40.0}"
: "${GRAVITY_KEYSTORE:?Set GRAVITY_KEYSTORE to your Android signing keystore}"
mkdir -p build/classes build/dex
java -jar "$ECJ_JAR" -source 8 -target 8 -proc:none -nowarn -bootclasspath "$ANDROID_JAR" -classpath "$XPOSED_JAR" \
    -d build/classes src/dev/gravitation/pixel/*.java
(cd build/classes && zip -q -r ../classes.jar .)
"$BUILD_TOOLS/d8" --release --min-api 31 --lib "$ANDROID_JAR" --classpath "$XPOSED_JAR" \
    --output build/dex build/classes.jar
"$BUILD_TOOLS/aapt2" compile --dir res -o build/resources.zip
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    -A assets -o build/base.apk build/resources.zip
(cd build/dex && zip -q -j ../base.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks "$GRAVITY_KEYSTORE" --out build/PixelGravity.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose build/PixelGravity.apk
