#!/usr/bin/env bash
# 打包「灵感」v0.11 APK。
# 兼容原手机端 arm64 Linux（proot Ubuntu + qemu-x86_64）以及 macOS 本机编译验证。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"
OUT_DIR="${OUT_DIR:-/sdcard/Download/DSH-Go}"
VERSION_CODE="${VERSION_CODE:-13}"
VERSION_NAME="${VERSION_NAME:-0.11}"
APK_NAME="${APK_NAME:-灵感-v${VERSION_NAME}.apk}"

OS_NAME="$(uname -s)"
if [ "$OS_NAME" = "Darwin" ]; then
    export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
    export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
    SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
    BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
    ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
    AAPT2=("$BUILD_TOOLS/aapt2")
    D8_CMD=("$BUILD_TOOLS/d8")
    ZIPALIGN_BIN="$BUILD_TOOLS/zipalign"
    SIGNER=("$BUILD_TOOLS/apksigner")
else
    SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
    ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
    R8_JAR="${R8_JAR:-/opt/android-tools/r8.jar}"
    APKSIGNER_JAR="${APKSIGNER_JAR:-/opt/android-tools/apksigner-35.jar}"
    AAPT2_BIN="${AAPT2_BIN:-/opt/android-tools/aapt2-35}"
    QEMU_X86="${QEMU_X86:-qemu-x86_64}"
    command -v "$QEMU_X86" >/dev/null || { echo "缺少 $QEMU_X86" >&2; exit 1; }
    test -x "$AAPT2_BIN" || { echo "缺少 aapt2: $AAPT2_BIN" >&2; exit 1; }
    test -f "$R8_JAR" || { echo "缺少 D8/R8 jar: $R8_JAR" >&2; exit 1; }
    AAPT2=("$QEMU_X86" "$AAPT2_BIN")
    D8_CMD=(java -cp "$R8_JAR" com.android.tools.r8.D8)
    ZIPALIGN_BIN="zipalign"
    if [ -f "$APKSIGNER_JAR" ]; then
        SIGNER=(java -jar "$APKSIGNER_JAR")
    else
        SIGNER=(apksigner)
    fi
fi

for tool in javac keytool python3; do
    command -v "$tool" >/dev/null || { echo "缺少 $tool" >&2; exit 1; }
done
test -f "$ANDROID_JAR" || { echo "缺少 android.jar: $ANDROID_JAR" >&2; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

"${AAPT2[@]}" compile --dir "$ROOT/res" -o "$BUILD/res.zip"
"${AAPT2[@]}" link -o "$BUILD/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    "$BUILD/res.zip" \
    --min-sdk-version 29 \
    --target-sdk-version 34 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --replace-version

javac --release 11 -encoding UTF-8 -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" $(find "$ROOT/java" -name '*.java')

"${D8_CMD[@]}" \
    --release --min-api 29 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class')

python3 - "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" "$BUILD/withdex.apk" <<'PY'
import shutil, sys, zipfile
src, dex, out = sys.argv[1:4]
shutil.copyfile(src, out)
with zipfile.ZipFile(out, "a") as zf:
    zf.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)
PY

"$ZIPALIGN_BIN" -f 4 "$BUILD/withdex.apk" "$BUILD/aligned.apk"

KEYSTORE="${KEYSTORE:-$ROOT/keystore/release.keystore}"
KEY_ALIAS="${KEY_ALIAS:-inboxcapture}"
KEY_PASS="${KEY_PASS:-inboxcapture}"
IS_TEMP_SIGN=0
if [ ! -f "$KEYSTORE" ] && [ "${ALLOW_TEMP_DEBUG_KEY:-0}" = "1" ]; then
    IS_TEMP_SIGN=1
    KEYSTORE="$BUILD/temp-debug.keystore"
    KEY_ALIAS="tempdebug"
    KEY_PASS="tempdebug"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 365 \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -dname "CN=TempDebug,O=Test,C=CN" >/dev/null 2>&1
    echo "注意：使用了临时测试签名（仅用于验证编译打包，不覆盖正式版输出）"
fi
test -f "$KEYSTORE" || { echo "缺少签名密钥: $KEYSTORE" >&2; exit 1; }

OUT_APK="$BUILD/InboxCapture.apk"
if [ "$IS_TEMP_SIGN" = "1" ]; then
    OUT_APK="$BUILD/InboxCapture-debug-verify.apk"
fi

"${SIGNER[@]}" sign --ks "$KEYSTORE" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
    --out "$OUT_APK" "$BUILD/aligned.apk"
"${SIGNER[@]}" verify --verbose "$OUT_APK" >/dev/null
echo "APK: $OUT_APK"

if [ "$IS_TEMP_SIGN" = "0" ] && [ -d "$OUT_DIR" ]; then
    cp "$OUT_APK" "$OUT_DIR/$APK_NAME"
    chmod 644 "$OUT_DIR/$APK_NAME" 2>/dev/null || true
    echo "已复制: $OUT_DIR/$APK_NAME"
fi
