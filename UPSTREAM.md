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
  4. `build: name release artifact danmaku-jellyfintv`

## 跟版记录

| 日期 | 上游基线 | 产品版本 | 产物 | 结果 |
| --- | --- | --- | --- | --- |
| 2026-09-15 | `upstream/master` `3d087faa0`（2026-09-13） | 1.1.1 | `danmaku-jellyfintv-v1.1.1.apk`（49.1 MB，SHA-256 前 16 位 `472D9BC61B76B8DD`） | rebase 成功，`assembleDebug` 通过；缓冲策略已交还上游 |
| 2026-07-21 | `master` `14a5e160e`（= v0.19.9，2026-05-03） | 1.0.0 | `danmaku-jellyfintv-v1.0.0.apk`（27.2 MB） | 初始版本 |

> 上游 `master` 与 `release-0.19.z` 已分叉：`v0.19.10` 的三个修复在 `master` 上都有对应提交（cherry-pick 的原版），所以基于 `master` 跟版不会丢这些修复。

## 跟版步骤

```bash
git fetch upstream --tags
git rebase upstream/master          # 冲突通常集中在 AppModule.kt 的 import 区
./gradlew :app:assembleDebug        # 版本号取自 gradle.properties 的 jellyfin.version
git push --force-with-lease origin danmaku
```

## 编译与签名

- 编译环境：JDK 21（`~/.gradle/jdks` 已缓存）、Android SDK platform 37。
- 产物：`app/build/outputs/apk/debug/danmaku-jellyfintv-v<版本>-debug.apk`
- 版本号：`gradle.properties` 的 `jellyfin.version`（当前 `v1.1.1`）。
- 签名：目前使用 Android **调试签名**（与 1.0.0 一致），`applicationId` 为 `org.jellyfin.androidtv.debug`，可直接覆盖安装旧版。
  若改用自有密钥的 release 签名，包名与签名都会变，需要先卸载旧版再安装。

## 缓冲策略：已交还上游

本项目曾自行把 ExoPlayer 前向缓冲改成 10~20 分钟并覆盖上游设置。2026-09-15 起改回**完全使用上游的缓冲档位**（`preference/constant/BufferLength.kt`，设置路径：设置 → 播放 → 高级 → 缓冲）：

| 档位 | 最小缓冲 | 最大缓冲 | 起播 | 重缓冲后起播 |
| --- | --- | --- | --- | --- |
| AUTO | 库默认（50 秒） | 库默认（50 秒） | 1 秒 | 2 秒 |
| LARGE | 50 秒 | 120 秒 | 2.5 秒 | 5 秒 |
| EXTRA_LARGE | 80 秒 | 240 秒 | 5 秒 | 10 秒 |

不显式设置 `setTargetBufferBytes` 时，Media3 会按轨道自动计算字节预算，并钳制在 12.5 MB ~ 200 MB 之间。也就是说 EXTRA_LARGE 的实际上限是「240 秒或约 200 MB，先到者为准」——码率高于约 6.7 Mbps 时先撞字节上限。对慢速/卫星线路仍建议选 EXTRA_LARGE。

## 已知冲突热点

| 文件 | 原因 |
| --- | --- |
| `app/src/main/java/org/jellyfin/androidtv/di/AppModule.kt` | 上游持续新增 Koin 绑定，import 区几乎每次都会冲突 |
| `app/src/main/res/values*/strings.xml` | 上游每周有 Weblate 翻译提交，文案冲突量大但易解 |
