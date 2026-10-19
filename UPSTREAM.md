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
  5. `perf(player): raise ExoPlayer forward buffer to 10-20 minutes`

## 跟版记录

| 日期 | 上游基线 | 产品版本 | 产物 | 结果 |
| --- | --- | --- | --- | --- |
| 2026-10-20 | `upstream/master` `3d087faa0`（2026-09-13） | 1.2.1 | `danmaku-jellyfintv-v1.2.1-personal.apk`（51.5 MB，`C5FB551E82A0488E`）/ `danmaku-jellyfintv-v1.2.1-public.apk`（51.5 MB，`10D8AB6DCE90A65B`） | 新增 personal/public 构建变体；public 不含 Token 并带首次配置，personal 由 `local.properties` 预注入；两套 debug 均编译、签名通过 |
| 2026-10-20 | `upstream/master` `3d087faa0`（2026-09-13） | 1.2.0 | `danmaku-jellyfintv-v1.2.0.apk`（55.0 MB，SHA-256 前 16 位 `214FEB970B50951D`） | 弹幕源切换为自建 misaka（`match` + `comment`），移除 dandanplay/服务端 XML 分支；`assembleDebug` 通过 |
| 2026-09-15 | `upstream/master` `3d087faa0`（2026-09-13） | 1.1.1 | `danmaku-jellyfintv-v1.1.1.apk`（49.3 MB，SHA-256 前 16 位 `1A3DB882BF53962D`） | rebase 成功，`assembleDebug` 通过；缓冲保留本项目覆盖 |
| 2026-07-21 | `master` `14a5e160e`（= v0.19.9，2026-05-03） | 1.0.0 | `danmaku-jellyfintv-v1.0.0.apk`（27.2 MB） | 初始版本 |

> 上游 `master` 与 `release-0.19.z` 已分叉：`v0.19.10` 的三个修复在 `master` 上都有对应提交（cherry-pick 的原版），所以基于 `master` 跟版不会丢这些修复。

## 跟版步骤

```bash
git fetch upstream --tags
git rebase upstream/master          # 冲突通常集中在 AppModule.kt 的 import 区
./gradlew :app:assemblePersonalDebug
./gradlew :app:assemblePublicDebug
git push --force-with-lease origin danmaku
```

## 编译与签名

- 编译环境：JDK 21（`~/.gradle/jdks` 已缓存）、Android SDK platform 37。
- 产物：
  - personal：`app/build/outputs/apk/personal/debug/danmaku-jellyfintv-v<版本>-personal-debug.apk`
  - public：`app/build/outputs/apk/public/debug/danmaku-jellyfintv-v<版本>-public-debug.apk`
- 版本号：`gradle.properties` 的 `jellyfin.version`（当前 `v1.2.1`）。
- 构建变体：
  - `personal`：`applicationId=org.jellyfin.androidtv.debug`，从 `local.properties` 的 `misaka.api.base`（或环境变量 `MISAKA_API_BASE`）注入 Token。**只在本机使用，不提交、不上传公开 Release。**
  - `public`：`applicationId=org.jellyfin.androidtv.public.debug`，`MISAKA_API_BASE_URL` 固定为空；首次播放时弹配置框，用户填写自己的 misaka 地址和 Token。可以公开分发。
- Token 安全：`local.properties` 已被 gitignore，Token 不进入源码；personal APK 本身仍包含 Token，不能公开分发。public APK 已用 dex 扫描确认不含 Token。
- TV 的 `app_id` / 搜索 provider authority 通过 `androidComponents.onVariants` 按最终 `applicationId` 写入，public 包的 authority 为 `org.jellyfin.androidtv.public.debug.content`。
- 签名：目前使用 Android **调试签名**（与 1.0.0 一致），personal 包名 `org.jellyfin.androidtv.debug`，可与 public 包同时安装。
  若改用自有密钥的 release 签名，包名与签名都会变，需要先卸载旧版再安装。

## 缓冲策略：本项目覆盖上游档位（有意保留）

上游自带缓冲档位（`preference/constant/BufferLength.kt`，设置路径：设置 → 播放 → 高级 → 缓冲）：

| 档位 | 最小缓冲 | 最大缓冲 | 起播 | 重缓冲后起播 |
| --- | --- | --- | --- | --- |
| AUTO | 库默认（50 秒） | 库默认（50 秒） | 1 秒 | 2 秒 |
| LARGE | 50 秒 | 120 秒 | 2.5 秒 | 5 秒 |
| EXTRA_LARGE | 80 秒 | 240 秒 | 5 秒 | 10 秒 |

**为什么本项目仍然覆盖它**：不显式设置 `setTargetBufferBytes` 时，Media3 1.11 会按轨道自动计算字节预算，并钳制在 12.5 MB ~ 200 MB 之间。也就是说上游 EXTRA_LARGE 的实际上限是"240 秒或约 200 MB，先到者为准"，码率高于约 6.7 Mbps 时先撞字节上限。在慢速/跨运营商线路上实测不够用，因此本项目在 `VideoManager.configureExoplayerBuilder` 中于上游 `BufferLength` 分支之后**无条件覆盖** `loadControl`：

- 前向缓冲 600 000 / 1 200 000 毫秒（10 ~ 20 分钟）
- 字节预算 = 应用堆上限的 70%，上限 1 GB
- back buffer 按 50 Mbps 最坏情况取字节预算的 1/3（约 19 ~ 38 秒）

代价：设置里的"缓冲"档位对本项目无效；该覆盖块也是跟版时的已知冲突点（变量名复用上游声明的 `loadControl`，曾经因重复声明报过编译错误）。

> 2026-09-15 曾短暂改为完全使用上游档位，同日按实际使用体验回退，保留本项目逻辑；手机端（`jellyfin-android`）使用同源的自定义缓冲。

## 发布 (Release)

每次跟版出包后，在 GitHub 上发一个 release：

| 项 | 值 |
| --- | --- |
| tag | `v<版本号>`，与 `gradle.properties` 的 `jellyfin.version` 一致 |
| 目标分支 | `danmaku` |
| 标题 | `Danmaku Jellyfin Android TV v<版本> (弹幕版)` |
| 附件 | **只上传 public 包**：`danmaku-jellyfintv-v<版本>-public.apk`；personal 包含 Token，禁止上传 |
| 说明 | 双语，含本版跟版记录与安装注意事项 |

已发布：`v1.0.0`（2026-07-21，指向 `main`）、`v1.1.1`（2026-09-15，指向 `danmaku`）。

发布用的是仓库 owner 的 GitHub OAuth 凭据（本机由 Git 凭据管理器保存），调用 REST API 完成；没有安装 `gh` CLI，用 `Invoke-RestMethod` 即可，无需额外配置。

## 已知冲突热点

| 文件 | 原因 |
| --- | --- |
| `app/src/main/java/org/jellyfin/androidtv/di/AppModule.kt` | 上游持续新增 Koin 绑定，import 区几乎每次都会冲突 |
| `app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java` | 上游的 `BufferLength` 分支之后被本项目覆盖为长缓冲，注意 `loadControl` 变量只需声明一次 |
| `app/src/main/res/values*/strings.xml` | 上游每周有 Weblate 翻译提交，文案冲突量大但易解 |
