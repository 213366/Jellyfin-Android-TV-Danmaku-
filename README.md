<h1 align="center">Jellyfin Android TV (Danmaku 弹幕版)</h1>

> [!IMPORTANT]
> **本仓库的弹幕功能需要自行部署 misaka / Danmaku requires a self-hosted Misaka server**
>
> 本 fork 的弹幕分支已停用原版弹弹play API 和 `jellyfin-plugin-danmu` XML 接口，改为 [misaka](https://github.com/l429609201/misaka_danmu_server)。
> public 包不内置服务地址或 Token；请自行部署 misaka，并在首次播放时填写地址和 Token。Releases 只发布 public 包。
>
> **English:** deploy your own Misaka instance. The public build contains no server address or token and asks for them on first playback.

---

> **增强版 Fork** - 本项目 Fork 自 [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv)，新增弹幕功能和播放缓冲。

## 新增功能 / New Features

### 弹幕功能 (Danmaku Support)
视频播放时支持实时弹幕/评论叠加显示。
Real-time danmaku comment overlay during video playback.
<img width="926" height="530" alt="image" src="https://github.com/user-attachments/assets/a49d521a-245b-4b6d-93bd-7bd6fa72406f" />

### 播放缓冲 10-20 分钟 (Playback Buffer 10-20 min)
增加了 10-20 分钟的播放缓冲，解决跨运营商播放卡顿问题，让跨网播放更流畅。
Added 10-20 minute playback buffer for smoother cross-ISP streaming.

---

## 致谢 / Acknowledgments

- 原项目：[jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv)
- 弹幕参考：[Izumiko/jellyfin-danmaku](https://github.com/Izumiko/jellyfin-danmaku)

---

*代码由 [TRAE](https://trae.ai) + DeepSeek V4 Pro 生成*
