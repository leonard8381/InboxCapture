# 灵感 App 构建说明

包名为 `com.zao.inspirationbox`，版本为 `0.15-test`（versionCode 23），最低支持 Android 10（API 29），目标为 API 34。应用使用 Java，随 APK 分发 Android 版 Syncthing v2.1.7。

## 构建环境

- Bash、JDK 17、Python 3。
- Android SDK Platform 35 和 Build Tools 35.0.0。通过 `ANDROID_SDK_ROOT` 指定 SDK 路径。需要覆盖工具目录时，可设置 `ANDROID_BUILD_TOOLS`。
- arm64 Linux 的原有 proot 环境也可继续通过 qemu-x86_64 运行 aapt2，并使用 `ANDROID_JAR`、`AAPT2_BIN`、`R8_JAR` 和 `APKSIGNER_JAR` 指定已安装的工具。

## 构建与签名

从仓库根目录执行。一次性编译验证可使用临时签名。

```bash
ALLOW_TEMP_DEBUG_KEY=1 ./build.sh
```

正式构建需准备自己的签名文件，默认读取 `keystore/release.keystore`。也可使用环境变量 `KEYSTORE`、`KEY_ALIAS`、`KEY_PASS` 指定密钥及凭据，然后执行 `./build.sh`。后续覆盖更新必须使用兼容签名并提高 versionCode。

正式包输出到 `build/InboxCapture.apk`，使用临时签名时输出到 `build/InboxCapture-debug-verify.apk`。如果 `OUT_DIR` 存在，脚本还会将正式包复制到那里，默认目录为 `/sdcard/Download/DSH-Go`。

## Syncthing 依赖

第一次构建时，脚本下载固定的 Syncthing-Fork v2.1.7.1 通用 APK，校验整包 SHA-256，并按 `assets/syncthing/SHA256SUMS.txt` 逐个校验 arm64-v8a、armeabi-v7a、x86、x86_64 的引擎。上游包与解出的引擎保存在 `build/deps/`，后续构建可复用。上游源码、许可证及通用 APK 校验和见[第三方声明](assets/syncthing/NOTICE.txt)。[上游发行页](https://github.com/researchxxl/syncthing-android/releases/tag/v2.1.7.1)

已持有相同版本的通用 APK 时，可离线还原这项依赖。

```bash
SYNCTHING_APK=/path/to/syncthing-fork-universal.apk python3 scripts/prepare_syncthing.py
```

完全离线构建时，在运行 `./build.sh` 时也设置同一个 `SYNCTHING_APK`，或者将上游包放入 `build/deps/com.github.catfriend1.syncthingfork_release_v2.1.7.1.apk`。

## 首次配对资料

首次连接时，通用应用使用已授权手机收纳目录的 `release/syncthing-peer.json` 查找电脑。先将此文件复制或同步到手机，UTF-8 JSON 的格式如下。

```json
{
  "id": "替换为电脑 Syncthing 的完整设备 ID",
  "address": "192.168.1.10:22000"
}
```

将示例地址换为手机所在局域网可达的电脑私有 IP 与同步端口。应用将所粘贴的 ID 与此文件匹配，并由 Syncthing 在握手时校验电脑的证书。已有配对会保存在设备上。

如需为自己的电脑私下制作专用包，可在本地 `assets/syncthing-peer.json` 放置同格式资料。该文件已在 Git 忽略规则内。
