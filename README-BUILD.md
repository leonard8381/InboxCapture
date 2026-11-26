# 灵感 App · 构建说明

- 包名：`com.zao.inspirationbox`
- 版本：`0.11` / versionCode 13
- minSdk 29，targetSdk 34
- 语言：Java（无 AndroidX，无第三方依赖）

## 依赖

- JDK 17
- Android SDK Platform 35（`android.jar`）
- newer `aapt2`（本机 arm64 通过 `qemu-x86_64` 运行 build-tools 35 的 aapt2）
- `d8` / `r8` jar 8.9.35
- `zipalign`、`apksigner`、`keytool`

## 构建

```bash
./build.sh
```

输出：

- `build/InboxCapture.apk`
- 复制到 `/sdcard/Download/DSH-Go/灵感-v0.11.apk`

脚本默认路径：

- `ANDROID_JAR=/opt/android-sdk/platforms/android-35/android.jar`
- `AAPT2_BIN=/opt/android-tools/aapt2-35`
- `R8_JAR=/opt/android-tools/r8.jar`
- `KEYSTORE=keystore/release.keystore`

## 签名密钥

本仓库**不包含** `keystore/release.keystore`（私钥已通过 `.gitignore` 忽略）。

- 首次自行构建可使用环境变量开启临时测试签名：`ALLOW_TEMP_DEBUG_KEY=1 ./build.sh`
- 如需正式签名并长期覆盖升级，请在本地生成自己的 keystore 放到 `keystore/release.keystore`，并通过环境变量 `KEYSTORE`、`KEY_ALIAS`、`KEY_PASS` 传入（升级同一包名需始终使用同一把签名密钥）。
