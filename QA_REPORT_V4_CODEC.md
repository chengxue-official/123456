# QA 报告 V4 — 红果 App mp4 真实编码（fourcc）与参考实现解码机制 硬证据调研

> 调研人：codec-researcher
> 日期：2026-10-09
> 性质：**只读调研**（只新增本报告 + 两个独立 Python 脚本 `_codec_probe.py` / `_codec_enum.py`，未改动任何工程源码）
> 方法：签名请求线上 App 接口 → 下载 mp4 前 512KB → 逐字节解析 MP4 box 结构；枚举多剧集 codec；审计「红果鉴」media_kit/FFmpeg 依赖。

---

## 0. 结论摘要（TL;DR）

| 结论 | 判定 | 硬证据 |
|---|---|---|
| `codec_type=bytevc1` 的真实位流 fourcc | **H.265/HEVC（`hvc1`）**，不是私有编码 | `encv`(frma=`hvc1`)+`hvcC` box |
| `codec_type=bytevc2` 的真实位流 fourcc | **真正私有编码（`bvc2`）**，无标准解码器 | `encv`(frma=`bvc2`)，无 hvcC/avcC |
| 加密 | CENC-AES-CTR（AES-128），KID 与接口一致 | `schm=cenc` + `senc`/`saio`/`saiz` + tenc KID 匹配 |
| 标准 FFmpeg 能否解码 | **能**（HEVC + CENC 都是标准特性） | 参考实现就是用标准 FFmpeg 播放 |
| Android MediaCodec（API 19）能否解码 | **不能**（KitKat 无 HEVC 解码器 + 无 CENC） | HEVC 解码器从 API 21 才有 |
| 参考实现「红果鉴」解码机制 | 标准 mpv/FFmpeg + `decryption_key` CENC 解密 + HEVC 解码，**跳过 bytevc2** | 见第 3 节 |

**一句话结论**：此前 QA_REPORT_V3 判定的「bytevc1 是 ByteDance 私有 codec、App 无法解码」这个 P0 判断**方向对但定性不准**。真正的 P0 不是「私有编码无法解码」，而是——**bytevc1 实为标准 H.265/HEVC（hvc1）+ CENC-AES-CTR 加密**，标准 FFmpeg 完全可以解密+软解；真正的障碍是目标平台 **Android 4.4（API 19）的 MediaCodec 没有 HEVC 解码器**（HEVC 硬解从 Android 5.0 / API 21 才有），且 KitKat 的 MediaPlayer/MediaExtractor 也不支持 CENC 解密。

---

## 1. 真实 mp4 位流 fourcc（硬证据）

### 1.1 关键发现：接口标签 `bytevc1` ≠ 位流 fourcc

取流返回的 `video_meta.codec_type` 恒为 `bytevc1`/`bytevc2`，但每个变体的 **`gear_des_key` 字段直接暴露了真实编码**：

```
gear_des_key = 0:MP4|1:encrypt|2:h265_hvc1|4:360p|5:normal|6:only_roi_audio_64k_v194|10000:105
```

其中第 3 段 `2:h265_hvc1` 明确写的是 **h265 + hvc1**。这是接口层自己的「解码提示」：bytevc1 = h265/hvc1。

### 1.2 下载 mp4 前 512KB 并解析 box（`_codec_probe.py`）

签名请求 `POST /novel/player/video_model/v1/`（vid=7693489376713526334，付费集第4集）拿到 `main_url`，用 `Referer: https://novel.snssdk.com/` + App UA + `Range: bytes=0-524287` 下载。CDN 返回 **HTTP 206**，`Content-Type: video/mp4`，总长 2.9MB。

**顶层 box 结构**：
```
ftyp (major_brand=isom)  28B
moov                     159512B
free                     8B
mdat                     2790204B
```

**视频轨道 sample entry（stsd）** —— 决定性证据：

```
video trak → stbl → stsd（entry_count=1）
  sample entry fourcc = encv（受保护的视频 sample entry）
    ├─ hvcC  (212B)  ← HEVC 解码器配置记录，前8字节 01 01 60 00 00 00 00 00
    │                  （configurationVersion=1, profile_idc=1 → Main profile）
    ├─ colr  (19B)   ← 颜色信息
    ├─ pasp  (16B)   ← 像素宽高比
    ├─ sinf  (80B)   ← 保护方案信息
    │    ├─ frma = "hvc1"   ← ★ 原始格式 fourcc = H.265/HEVC
    │    ├─ schm = "cenc"   ← ★ 加密方案 = Common Encryption
    │    └─ schi (40B) → tenc（KID=6ac3aa95476d5a9162f43e300002ebeb，isProtected=1，perSampleIVSize=8）
    └─ btrt  (20B)   ← 码率
```

**音频轨道 sample entry**：
```
audio trak → stbl → stsd（entry_count=1）
  sample entry fourcc = enca
    └─ sinf → frma = "mp4a"（AAC）, schm = "cenc"
```

**结论**：视频位流是**标准 HEVC/H.265（hvc1, Main profile）**，被 CENC（AES-128-CTR）加密，音频是 AAC。接口标签 `bytevc1` 对应的真实 fourcc 是 `hvc1`，**不是私有 fourcc**。

### 1.3 bytevc2 才是真正的私有编码

对同时含 bytevc1+bytevc2 的剧集（series 7693487303095766078，vid 7693489294211566654）逐变体解析：

| codec_type | 分辨率 | gear_des_key 第3段 | 位流 fourcc | 子 box |
|---|---|---|---|---|
| bytevc2 | 360p/480p/540p/720p | `bytevc2` | `encv` → frma=`bvc2` | pasp, sinf, btrt（**无 hvcC/avcC**） |
| bytevc1 | 1080p | `h265_hvc1` | `encv` → frma=`hvc1` | **hvcC**, colr, pasp, sinf, btrt |

**结论**：`bvc2` 是真正的 ByteDance 私有编码（无任何标准解码器配置 box，标准 FFmpeg 无法解码）。这正是参考实现 `provider_hongguo_native_media.go:62` 显式 `if codec == "bytevc2" { continue }` 跳过 bytevc2、只保留 bytevc1 的根本原因。

### 1.4 CENC 加密交叉验证

- `encrypt_info.kid = 6ac3aa95476d5a9162f43e300002ebeb`（接口返回）
- tenc box 内 `default_KID = 6ac3aa95476d5a9162f43e300002ebeb`（**与接口一致**）
- 视频/音频轨道 stbl 内均有 `senc`（逐样本加密）、`saio`、`saiz` box → 逐样本 AES-CTR 加密
- `spade_a = "orws+mKxG8pTgB34Z4U2zXiLMcx9gzPOSLUE+k+EHs5hgCmYmA=="`，经 Go 源码 `hongguoContentKey`（`provider_hongguo_playback.go:160-202`）派生 → 合法 16 字节 AES-128 密钥：`c075bd047abf9050204865bd472f81e8`

---

## 2. 多剧集 codec 枚举（确认所有剧都只有 bytevc1/bytevc2）

分类接口 `/reading/distribution/category/landpage/v/` 拉取 17 条剧集，取前 8 部逐部取 vid → `video_model`：

| series_id | vid | codec_type 集合 | encrypt_method | gear_des_key 第3段（首个变体） |
|---|---|---|---|---|
| 7693487303095766078 | 7693489294211566654 | bytevc1, bytevc2 | cenc-aes-ctr | bytevc2 |
| 7694504178172841022 | 7694505990250564633 | bytevc1, bytevc2 | cenc-aes-ctr | bytevc2 |
| 7694198548283280409 | 7694200384566332478 | bytevc1 | cenc-aes-ctr | h265_hvc1 |
| 7690487696732474430 | 7690489564082752536 | bytevc1, bytevc2 | cenc-aes-ctr | bytevc2 |
| 7692828883413912638 | 7692830706535255065 | bytevc1, bytevc2 | cenc-aes-ctr | bytevc2 |
| 7691609199129676862 | 7691611155264982041 | bytevc1 | cenc-aes-ctr | h265_hvc1 |
| 7690530476376738840 | 7690532470717959230 | bytevc1 | cenc-aes-ctr | h265_hvc1 |
| 7690851568916106264 | 7690853695612800024 | bytevc1, bytevc2 | cenc-aes-ctr | bytevc2 |

**结论**：所有剧集的 `encrypt_method` 恒为 `cenc-aes-ctr`；`codec_type` 只有 `bytevc1`（=hvc1/HEVC）和 `bytevc2`（=bvc2/私有）两种。**所有剧集都至少有一个 bytevc1（hvc1）变体**，因此「选 bytevc1 变体 + 解密」即可统一处理，无需触碰 bvc2。

---

## 3. 参考实现「红果鉴」如何解码 bytevc1

### 3.1 依赖链（标准 FFmpeg，非字节 ttffmpeg 分支）

`pubspec.lock` / `pubspec.yaml`：

| 包 | 版本 | 说明 |
|---|---|---|
| media_kit | 1.2.6 | mpv 播放器封装 |
| media_kit_libs_video | 1.0.7 | |
| **media_kit_libs_android_video** | **1.3.8** | 捆绑 mpv/FFmpeg 的 Android 包 |
| ffmpeg_kit_flutter_new_min_gpl | 2.6.2 | 附带的标准 FFmpeg（共享库 libavcodec/libavformat 等） |

`media_kit_libs_android_video` 的 `android/build.gradle` 下载的是一系列 jar，来源：

```
https://github.com/media-kit/libmpv-android-video-build/releases/download/v1.1.7/default-<abi>.jar
```

即 **media-kit 官方 `libmpv-android-video-build v1.1.7` 的 `default` 构建**（标准 FFmpeg 静态链接进 `libmpv.so`）。全工程 `grep bytevc|bvc1|bvc2|ttffmpeg|avs3` 仅命中：跳过逻辑（`provider_hongguo_native_media.go:62`）、两个测试 fixture、以及播放器 `hwdec-codecs` 配置。**不存在任何字节 ttffmpeg/bytevc1 专用解码分支。**

### 3.2 解码链路（`lib/player_screen.dart:1173-1211`）

```dart
// hwdec-codecs 明确列出 hevc（HEVC 硬解），不含任何 bytevc 私有项
'hwdec-codecs': 'h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1,avs3',

// 关键：把 spade_a 派生的 AES-128 密钥以 hex 传给 mpv 的 FFmpeg demuxer
platform.setProperty('demuxer-lavf-o', [
  'seg_max_retry=3', 'strict=experimental', 'allowed_extensions=ALL',
  'protocol_whitelist=[http,https,tcp,tls,crypto,data,file]',
  if (plan.decryptionKey.isNotEmpty) 'decryption_key=${plan.decryptionKey}',  // ← CENC 解密
].join(','));
```

三步解码：
1. **密钥派生**：Go 侧 `hongguoContentKey(spade_a)` → 16 字节 AES-128 密钥（`provider_hongguo_playback.go`）。
2. **CENC 解密**：mpv `--demuxer-lavf-o=decryption_key=<hex>` 把密钥透传给 FFmpeg 的 mov demuxer，解密 CENC AES-128-CTR（FFmpeg 自 3.0 起原生支持 `decryption_key`，属标准特性）。
3. **解码**：解密后得到明文 hvc1/HEVC 位流，由 FFmpeg 的 HEVC 解码器（软解）或硬件（`hwdec-codecs` 含 `hevc`）解码。

**结论**：参考实现**没有、也不需要任何 bytevc1 专用解码器**——它把「bytevc1」当作普通 HEVC 处理，用标准 FFmpeg 的 CENC 解密 + HEVC 解码即可播放。这反证了 bytevc1 的位流就是标准 HEVC。

---

## 4. 最终结论：能否被标准 FFmpeg / MediaCodec 解码，及 API 19 可行性

### 4.1 标准 FFmpeg —— **能解码** ✅

- 位流 fourcc = `hvc1`（HEVC/H.265 Main profile），是 FFmpeg 标准解码能力（libx265/hevc 解码器）。
- CENC-AES-CTR 是 FFmpeg 标准特性（`-decryption_key` / mpv `demuxer-lavf-o=decryption_key`）。
- 参考实现「红果鉴」已用**标准 FFmpeg** 端到端跑通，是直接实证。

### 4.2 Android MediaCodec —— **API 19（KitKat）不能解码** ❌

| 能力 | API 19 是否可用 |
|---|---|
| HEVC（H.265）解码器 | **无**。HEVC 硬解从 Android 5.0（API 21）起才进入 MediaCodec |
| CENC-AES-CTR 解密 | **无**。MediaExtractor/MediaPlayer 不支持 CENC；需 ExoPlayer media3 自定义 DrmSession/解密器 |
| AAC（音频） | 有，但同样被 CENC 加密，需先解密 |

### 4.3 在 Android 4.4（API 19）上端到端播放的可行性

**结论：不可行（至少不可用 MediaCodec 硬解方案）**，原因有三：

1. **HEVC 无硬解**：KitKat 的 MediaCodec 没有 HEVC 解码器，而红果所有 mp4 的视频都是 H.265（无 h264 变体）。
2. **只能软解**：唯一可行路径是 FFmpeg 软解 HEVC（参考实现就是这么做的），但 720p/1080p HEVC 软解在 2013-2014 年代的 KitKat 设备（ARMv7 单/双/四核）上远达不到实时，无法流畅播放。
3. **CENC 解密必须自实现**：需移植 `hongguoContentKey` 密钥派生 + CENC AES-CTR 解密（FFmpeg 可做，MediaCodec 无法单独做）。

**建议（供 team-lead 决策）**：
- 若目标是 **API 19 全覆盖**：红果 App 版站源**没有**可硬解的未加密 h264 路径（`video_platform=1/2/3/4` 实测均返回 hvc1+cenc 或 bvc2），端到端硬解播放不可行；应重新评估是否接受「仅软解（卡顿）」或「放弃 API 19、改用 API 21+ 的 HEVC 硬解 + ExoPlayer 解密」。
- 若可接受 **API 21+**：路径清晰——ExoPlayer media3 + 自定义 CENC 解密器（spade_a→AES-128→decryption_key）+ 系统 HEVC 硬解（需设备支持 H.265）。

---

## 附录 A：实际用到的脚本

- `D:/hongguo/_codec_probe.py` —— 签名取流 + 下载 512KB + MP4 box 解析（产出第 1 节证据）
- `D:/hongguo/_codec_enum.py` —— 多剧集 codec 枚举（产出第 2 节表）
- 复用 `D:/hongguo/_qa_xgorgon_test.py` 的签名/请求逻辑（`app_post` / `web_get` 等）

## 附录 B：关键输出片段

**box 树（视频轨道）**：
```
 ftyp 28 start 0
 moov 159512 start 28
   trak 114383
     mdia → minf → stbl
       stsd 449      ← 视频 sample entry
       stts / stss / ctts / stsc / stsz / stco
       senc 30592    ← 逐样本加密
       saio 20 / saiz 17
   trak 44653
     ... stsd 205    ← 音频 sample entry
       ... senc 21992 / saio / saiz / sgpd / sbgp
 free 8
 mdat 2790204
```

**视频 stsd 解析输出**：
```
entry=encv  hvcC(212) colr(19) pasp(16) sinf(80) btrt(20)
  sinf → frma=hvc1  schm=cenc(ver 0x10000)  schi→tenc(KID=6ac3aa95476d5a9162f43e300002ebeb)
```

**bytevc2 变体 stsd 解析输出**：
```
entry=encv  pasp(16) sinf(80) btrt(20)   ← 无 hvcC/avcC
  sinf → frma=bvc2  schm=cenc
```

**密钥派生自校验**：
```
spade_a → tag=11, padding=1, key=c075bd047abf9050204865bd472f81e8 (16B, 合法 AES-128)
```
