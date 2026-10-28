# TV Video Player（原生 Android 客户端）

自研 Android 视频（短剧向）客户端，纯 Kotlin + Java、View 体系，无 Compose。Media3/ExoPlayer 播放，内置自实现签名层与多域容灾。

## 环境

| 项 | 版本 |
|---|---|
| JDK | 21（`gradle.properties`/IDE 指向 JDK21，低版本会报 class version 65 错误） |
| Android SDK | Platform 36 / Build-Tools 36.0.0 |
| AGP / Kotlin | 见根目录 `build.gradle.kts` 版本号 |
| 最低支持 | Android 5.0（API 21，HEVC+CENC 硬解） |

首次 clone：Android Studio 打开会自动生成 `local.properties`（SDK 路径），无需手写。依赖走根目录 `settings.gradle.kts` 配置的阿里云镜像，国内网络可直连。

## 构建

```bash
./gradlew.bat :app:assembleDebug --offline   # 有 gradle 缓存时
./gradlew.bat :app:assembleDebug             # 首次/无缓存，在线拉依赖
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 发版（内部约定）

发版走独立的发布仓（静态托管 + 版本检测），流程：

1. `app/build.gradle.kts` 里 `versionCode` +1、`versionName` 升位（**必须递增 versionCode**，否则客户端收不到更新）；
2. 构建产物拷入发布仓 `public/`，生成/更新 `update.json`（含 versionCode/versionName/sha256/更新说明）；
3. 发布仓提交并 push，托管平台自动部署。

## 签名注意

- 覆盖安装依赖**统一签名**。团队内部使用同一份 `debug.keystore`（不进 git，走私密渠道分发）。
- 换 keystore 会导致签名指纹不一致，老用户无法覆盖安装，只能卸载重装（丢本地数据）。
- 校验指纹：`keytool -list -v -keystore debug.keystore -alias androiddebugkey`。

## 目录结构

```
app/src/main/java/com/houg/tv/
├── net/            # 网络层：NetClient（检索/详情/播放地址，含多域容灾、补季召回）、局域网投喂
├── sign/           # 自实现签名层：请求签名、SM3、媒体解密（CENC）
├── playback/       # CENC MP4 下载/解密/重封装（本地无缝播放）
├── repo/           # 本地索引（DramaIndex：分类页/搜索加速，编辑距离容错）
├── data/           # Room：观看历史、搜索历史
├── update/         # 版本检测与升级弹窗
└── util/           # 日志、崩溃上报、二维码、拼音等
```

## 关键机制速查

- **检索三路并行**：全库搜索（权威但慢）/ 联想词（快）/ 网页搜索页兜底，按 id 去重合并。
- **多季补全**：详情页与搜索页对“第 N 季”剧做逐季并行探测（全库检索、按批延伸、整批空手即停），保证系列季数召回齐全。
- **播放容灾**：多 CDN 域名池轮询；CENC 加密片源走本地下载→解密→重封装→无缝起播；25s 起播失败回退软解。
- **日志**：AppLog 单文件轮转（两代），可上传远端日志接口，崩溃自动附带。

## 已知取舍

- 联想词接口快但词表不全，多季识别必须走逐季探测（慢而准）。
- 包名与 applicationId 保持历史值不变（签名/升级兼容），勿随意改动。
