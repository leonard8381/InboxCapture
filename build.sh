#!/usr/bin/env bash
# 打包「灵感」APK。
# 兼容原手机端 arm64 Linux（proot Ubuntu + qemu-x86_64）以及 macOS 本机编译验证。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build/package"
OUT_DIR="${OUT_DIR:-/sdcard/Download/DSH-Go}"
VERSION_CODE="${VERSION_CODE:-$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml")}"
VERSION_NAME="${VERSION_NAME:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml")}"
APK_NAME="${APK_NAME:-灵感-v${VERSION_NAME}.apk}"

OS_NAME="$(uname -s)"
if [ "$OS_NAME" = "Darwin" ]; then
    export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
    export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
    SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
else
    SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
fi
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
if [ "$OS_NAME" = "Darwin" ] || [ -n "${ANDROID_BUILD_TOOLS:-}" ] || { [ "$(uname -m)" = "x86_64" ] && [ -x "$BUILD_TOOLS/aapt2" ]; }; then
    AAPT2=("$BUILD_TOOLS/aapt2")
    D8_CMD=("$BUILD_TOOLS/d8")
    ZIPALIGN_BIN="$BUILD_TOOLS/zipalign"
    SIGNER=("$BUILD_TOOLS/apksigner")
else
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
python3 "$ROOT/scripts/prepare_syncthing.py"

"${AAPT2[@]}" compile --dir "$ROOT/res" -o "$BUILD/res.zip"
"${AAPT2[@]}" link -o "$BUILD/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -A "$ROOT/assets" \
    "$BUILD/res.zip" \
    --min-sdk-version 29 \
    --target-sdk-version 34 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --replace-version

sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(find "$ROOT/java" -name '*.java' -print0)
javac --release 11 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$BUILD/classes" "${sources[@]}"

classes=()
while IFS= read -r -d '' file; do classes+=("$file"); done < <(find "$BUILD/classes" -name '*.class' -print0)
"${D8_CMD[@]}" \
    --release --min-api 29 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "${classes[@]}"

python3 - "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" "$BUILD/withdex.apk" "$ROOT/build/deps/native" <<'PY'
import pathlib, shutil, sys, zipfile
src, dex, out, native = sys.argv[1:5]
shutil.copyfile(src, out)
with zipfile.ZipFile(out, "a") as zf:
    zf.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
        core = pathlib.Path(native) / abi / "libsyncthing.so"
        zf.write(core, f"lib/{abi}/libsyncthing.so", compress_type=zipfile.ZIP_DEFLATED)
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

OUT_APK="$ROOT/build/InboxCapture.apk"
if [ "$IS_TEMP_SIGN" = "1" ]; then
    OUT_APK="$ROOT/build/InboxCapture-debug-verify.apk"
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
