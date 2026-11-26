# 灵感（Lingan）Android App

灵感是一款纯 Java（Android 10+）的本地收纳应用。复制小红书、抖音或微信公众号链接后，可在悬浮窗确认保存，生成 Markdown 笔记和截图。收纳列表支持查看原文、截图和删除记录。

## 手机和电脑文件夹同步

「灵感」将 Markdown 和截图写入手机本地文件夹。用 Syncthing 可以让该文件夹和 Windows 上的指定目录自动同步。

1. 手机上安装 [Syncthing-Fork](https://f-droid.org/packages/com.github.catfriend1.syncthingfork/)，电脑上安装 [Syncthing](https://syncthing.net/downloads/)。首次配置时，两台设备连接同一个 Wi-Fi，互相添加设备 ID。
2. 在「灵感 → 收纳设置」选择一个可写目录，例如手机的「文档/test」。在 Syncthing-Fork 里共享该目录，电脑接受共享后选择接收目录，例如桌面的「test1」。
3. 只需手机上传时，手机文件夹设为「仅发送」，电脑设为「仅接收」。需要两边互改时，两端都选「发送与接收」。手机删除源文件也会同步到电脑。
4. Windows 锁屏时保持联网、唤醒并让 Syncthing 运行。手机端在系统设置中允许 Syncthing-Fork 后台运行，应用的「运行条件」里允许用电池时通过 Wi-Fi 同步。

可在电脑锁屏后从手机收纳一条链接，再确认电脑端收到文件。 [Syncthing 配对说明](https://docs.syncthing.net/intro/getting-started.html)

## 构建说明

详见 [README-BUILD.md](README-BUILD.md)。

- 正式构建（需本地 `keystore/release.keystore`）：`./build.sh`
- 本机无密钥编译验证（macOS / Linux）：`ALLOW_TEMP_DEBUG_KEY=1 ./build.sh`
