# 上游跟版记录（Jellyfin for Android TV 弹幕版）

本仓库是 [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) 的 fork。
自研的弹幕改动全部集中在 `danmaku` 分支，官方主线保持只读。

## 远程与分支

| 远程 | 地址 | 用途 |
| --- | --- | --- |
| `origin` | https://github.com/213366/Jellyfin-Android-TV-Danmaku- | 自研分支的推送目标 |
| `upstream` | https://github.com/jellyfin/jellyfin-androidtv | 官方仓库（只读） |

- 自研分支：`danmaku`，跟踪 `origin/danmaku`
- 官方基线：`upstream/master`
- 自研提交（在 `upstream/master` 之上）：
  1. `feat(danmaku): add danmaku core module`
  2. `feat(danmaku): wire danmaku into playback layer`
  3. `feat(danmaku): add danmaku UI resources`
  4. `perf(player): raise ExoPlayer forward buffer to 10-20 minutes`
  5. `build: name release artifact danmaku-jellyfintv`

## 跟版记录

| 日期 | 上游基线 | 产品版本 | 产物 | 结果 |
| --- | --- | --- | --- | --- |
| 2026-09-14 | `upstream/master` `3d087faa0`（2026-09-13） | 1.1.0 | `danmaku-jellyfintv-v1.1.0.apk`（49.1 MB，SHA-256 前 16 位 `121D60D3D5D4FB05`） | rebase 成功，`assembleDebug` 通过 |
| 2026-07-21 | `master` `14a5e160e`（= v0.19.9，2026-05-03） | 1.0.0 | `danmaku-jellyfintv-v1.0.0.apk`（27.2 MB） | 初始版本 |

> 上游 `master` 与 `release-0.19.z` 已分叉：`v0.19.10` 的三个修复在 `master` 上都有对应提交（cherry-pick 的原版），所以基于 `master` 跟版不会丢这些修复。

## 跟版步骤

```bash
git fetch upstream --tags
git rebase upstream/master          # 冲突通常集中在 AppModule.kt 的 import 与 VideoManager.java
./gradlew :app:assembleDebug '-Pjellyfin.version=<新版本号>'
git push --force-with-lease origin danmaku
```

## 编译与签名

- 编译环境：JDK 21（`~/.gradle/jdks` 已缓存）、Android SDK platform 37。
- 产物：`app/build/outputs/apk/debug/danmaku-jellyfintv-v<版本>-debug.apk`
- 签名：目前使用 Android **调试签名**（与 1.0.0 一致），`applicationId` 为 `org.jellyfin.androidtv.debug`，可直接覆盖安装旧版。
  若改用自有密钥的 release 签名，包名与签名都会变，需要先卸载旧版再安装。

## 已知冲突热点

| 文件 | 原因 |
| --- | --- |
| `app/src/main/java/org/jellyfin/androidtv/di/AppModule.kt` | 上游持续新增 Koin 绑定，import 区几乎每次都会冲突 |
| `app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java` | 上游自带 `BufferLength` 缓冲档位，本项目在其后覆盖为长缓冲，需注意变量名冲突 |
| `app/src/main/res/values*/strings.xml` | 上游每周有 Weblate 翻译提交，文案冲突量大但易解 |
