#!/bin/bash
# Manual APK pipeline (Gradle daemon IPC is broken in this VM):
# aapt2 (generates R.java) -> javac -> d8 -> zip -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
export JAVA_HOME=~/workspace/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
SDK=~/workspace/android-sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar

rm -rf build && mkdir -p build/classes build/gen build/res-compiled build/dex

echo "== aapt2 compile =="
find res -type f | while read -r f; do
  $BT/aapt2 compile -o build/res-compiled "$f"
done

echo "== aapt2 link (also generates R.java) =="
$BT/aapt2 link -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen --auto-add-overlay -A assets build/res-compiled/*.flat

echo "== javac =="
find src -name "*.java" > build/sources.txt
find build/gen -name "*.java" >> build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "$AJAR" \
  -d build/classes @build/sources.txt

echo "== d8 =="
$BT/d8 --lib "$AJAR" --min-api 24 --output build/dex $(find build/classes -name "*.class")

echo "== package =="
cp build/base.apk build/unsigned.apk
cd build && zip -j unsigned.apk dex/classes.dex > /dev/null && cd ..
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f nill.keystore ]; then
  keytool -genkeypair -keystore nill.keystore -storepass android -keypass android \
    -alias nillkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Nill SuperProxy" 2>/dev/null
fi

echo "== sign =="
$BT/apksigner sign --ks nill.keystore --ks-pass pass:android --key-pass pass:android \
  --out out/NillSuperProxy.apk build/aligned.apk
$BT/apksigner verify --print-certs out/NillSuperProxy.apk | head -3
ls -lh out/NillSuperProxy.apk
echo "BUILD OK"
