# 灵感（Lingan）Android App

一款零第三方依赖、纯 Java（Android SDK 35 / minSdk 29）编写的个人效率 App，包含两个核心场景并全部本地写入 Obsidian `00-Inbox`：

1. **灵感收纳**：监听小红书、抖音、微信公众号链接复制，弹出悬浮窗并自动截屏，生成带 YAML frontmatter 的 Markdown 笔记与配图存入 Obsidian；支持列表查看完整正文与大图、跳回原 App 看原文、左滑同步删除仓库笔记与截图。
2. **力量训练记录**：
   - **快速记录与补录**：滚动 7 天时间条（前 5 天 + 今日 + 明日），支持补录历史日期；单区域 3×3 乘法胶囊点选板，自由杠铃按单侧片重（默认 10kg 空杆）自动折算总重。
   - **边练边记模式**：暗室聚光全屏训练界面，内置大字号重量/次数直选条与原地变形液体休息倒计时。

## 构建说明

详见 [README-BUILD.md](README-BUILD.md)。

- 正式构建（需本地 `keystore/release.keystore`）：`./build.sh`
- 本机无密钥编译验证（macOS / Linux）：`ALLOW_TEMP_DEBUG_KEY=1 ./build.sh`
