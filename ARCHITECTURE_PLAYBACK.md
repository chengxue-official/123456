# 红果短剧 TV 版 · 播放架构设计（HEVC + CENC 加密 mp4，minSdk 21）

> 作者：playback-architect　|　性质：只读设计任务，不修改工程源码
> 目标：在 Android 5.0（API 21）+ 上播放红果的 HEVC（`hvc1`）+ CENC-AES-128-CTR 加密 mp4，追求**最稳、最少返工**。

---

## 0. 结论速览（TL;DR）

| 方案 | 结论 | 一句话依据 |
|------|------|-----------|
| **A. ExoPlayer + ClearKey MediaDrm** | ❌ 不可行 | ExoPlayer 的 `Mp4Extractor` 对非分片 mp4 **不读 pssh**（ExoPlayer issue #8532），`drmInitData` 恒为 null；且本片源无 `pssh` box，`DefaultDrmSessionManager` 无法建会话。官方 DRM 矩阵里 ClearKey 只支持 **DASH**，不支持渐进式 mp4。 |
| **B. ExoPlayer + 自定义解密 DataSource** | ✅ **推荐** | 纯 Java、标准 Android 组件、无设备 DRM 依赖；CENC 解密逻辑确定；ExoPlayer 原生支持渐进 mp4 边下边播 + seek。**必须**在解密同时把 `stsd` 的 `encv/enca` 改回 `hvc1/mp4a` 并删掉 `senc/saio/saiz`。 |
| **C. FFmpeg/mpv 直连** | ⚠️ 可用但重 | 与参考实现同源，已被端到端实证；但引入 ~40MB native lib + GPL 许可 + JNI 集成，对本「传统 View + API21」工程偏重，作为 Plan C 兜底。 |
| **D. 全量下载+解密后播（FFmpeg `-decryption_key`）** | ⚠️ 可作为兜底/里程碑 | ffmpeg-kit **LTS** 版 minSdk=16 可跑在 API21；文件 2.9~30MB，全量下载+`-c copy` 解密耗时 1~15s 可接受。但 ffmpeg-kit **主版 minSdk=24** 冲突，必须用 LTS。 |

**最终推荐（一句话）**：用 **ExoPlayer media3 + 自研「CENC 解密 + 去封装」数据源（方案 B）**，HEVC 走 MediaCodec 硬解，首版以「全量下载→内存解密→明文 mp4 落盘→ExoPlayer 播本地文件」落地（最快、最稳），再迭代到流式解密 DataSource 实现真正的边下边播；无 HEVC 硬解的设备用 ffmpeg-kit LTS 转码 H264 兜底。

---

## 1. 关键事实与版本基线（已核实）

| 项目 | 事实 | 来源 |
|------|------|------|
| ClearKey MediaDrm 最低版本 | Android 5.0 / **API 21**，但官方矩阵标注仅支持 **DASH** | [ExoPlayer DRM 文档](https://developer.android.com/media/media3/exoplayer/drm) |
| ClearKey UUID（media3 常量） | `C.CLEARKEY_UUID = e2719d58-a985-b3c9-781a-b030af78d30e` | media3 `C.java` |
| media3 本地提供明文 key 的类 | `androidx.media3.exoplayer.drm.LocalMediaDrmCallback`（构造传 JSON `{"keys":[{"kty":"oct","k":"<b64key>","kid":"<b64kid>"}],"type":"temporary"}`） | [LocalMediaDrmCallback 官方 API](https://developer.android.com/reference/androidx/media3/exoplayer/drm/LocalMediaDrmCallback) |
| ExoPlayer 是否支持渐进式 CENC mp4 解密 | **不支持**：`Mp4Extractor` 只读 `encv` 的 `tenc.default_KID`，不读非分片 mp4 的 `pssh`，`drmInitData` 恒 null | [ExoPlayer issue #8532](https://github.com/google/ExoPlayer/issues/8532#issuecomment-771811707) |
| media3-decoder-ffmpeg 分发方式 | **不在 Google Maven**；需 clone `androidx/media`，用 NDK 自编译 FFmpeg 6.0 + libyuv，`ENABLED_DECODERS=(aac h264 hevc)`，`ANDROID_ABI=21` | [decoder_ffmpeg README](https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg) |
| ffmpeg-kit minSdk | 主版 **API 24**；**LTS 版 API 16**（`6.0.LTS` / `4.5.1.LTS`） | [ffmpeg-kit releases](https://github.com/arthenica/ffmpeg-kit/releases) |
| mpv-android | Maven Central `io.github.marlboro-advance:mpv-android:1.0.0`，**minSdk 21**，ABI armv7a/arm64/x86/x86_64 | [mpv-android](https://github.com/marlboro-advance/mpv-android) |
| HEVC 软件解码器 | `OMX.google.hevc.decoder`（`SoftHEVC`）自 Android 5.0 存在，但**不保证所有设备都有**；Main profile L3(手机)/L4.1(TV)，软解慢 | [AOSP SoftCodecs](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/media/codec2/...) / 第三方实测 |
| `AES/CTR/NoPadding` | `javax.crypto` 默认 provider（Conscrypt/BouncyCastle）自 API 1 起即可用，API21 无虞 | Android 官方 |
| CENC 8 字节 IV→16 字节规则 | 16 字节 CTR 计数块 = **8 字节 per-sample IV（高 64 位）‖ 8 字节 0（低 64 位块计数器，从 0 起，逐块大端自增）** | [oximedia-drm cenc 文档](https://docs.rs/oximedia-drm/latest/oximedia_drm/cenc/index.html)、[SodaDownloader 解密实现](https://zread.ai/baizeyv/SodaDownloader/10-aes-128-ctr-sample-decryption) |

---

## 2. 逐方案可行性评估

### 方案 A：ExoPlayer media3 + ClearKey MediaDrm 解密 CENC

**结论：不可行（在 API21 上走不通）。**

**依据：**

1. **DRM 会话无法建立**。ExoPlayer 的 `DefaultDrmSessionManager` 依赖 extractor 产出的 `DrmInitData` 才能确定 DRM scheme UUID 与 KID 并发起密钥请求。而对**渐进式（非分片）mp4**，`Mp4Extractor` 根本不解析 `pssh`（ExoPlayer 官方 issue #8532 明确承认：*"ExoPlayer's Mp4Extractor actually doesn't read pssh boxes. Currently we only read this info in fragmented MP4 files… the drmInitData still ends up null — meaning playback fails"*）。
2. **本片源没有 `pssh`**。红果 mp4 的 CENC 信令是 `schm=cenc` + `senc/saio/saiz` + `tenc`（`tenc` 只含 `default_KID` 与 `perSampleIVSize=8`），**没有** `pssh` box。即使 media3 后续给 `Mp4Extractor` 补了 pssh 读取，这里也无 pssh 可读 → `DrmInitData` 依然为 null → ClearKey 会话永远不触发。
3. **官方支持矩阵只列了 DASH**。ExoPlayer DRM 文档中 "ClearKey cenc → 5.0(21) → DASH"，渐进式 mp4 不是 ClearKey 的一等公民。
4. **设备依赖**：即使上述都解决，ClearKey 依赖设备的 ClearKey CDM（`frameworks/av/drm/mediadrm/plugins/clearkey`），Android 5.0 的电视盒子厂商可能裁剪该插件，`MediaDrm.isCryptoSchemeSupported(CLEARKEY_UUID)` 可能为 false。

**判定**：除非「下载后往 mp4 里注入 `pssh` + 换用 DASH 封装」，否则 A 无落地价值；而那样又回到了重封装，成本高于 B。

---

### 方案 B：ExoPlayer media3 + 自定义解密 DataSource（推荐）

**结论：可行，且是最稳、最少返工的主路径。**

**核心问题的答案（「解密 DataSource 需要做到哪一步」）：**

`Mp4Extractor` **不会**跳过/容忍 `encv/sinf/senc/saio/saiz`。它读到 `stsd` 里的 `encv/enca` → `sinf/tenc` 后会把该轨标记为「已加密（CENC）」，并把 `senc/saio/saiz` 当作逐样本加密信息；随后交给 MediaDrm 解密。因此**只解密 mdat 字节、保留 CENC box 是不行的**——ExoPlayer 会把解密后的字节再次送去 MediaDrm 解密，直接报错。

**解密 DataSource（或解密器）必须同时完成以下「去 CENC 重封装」：**

1. `stsd`：把 `encv` 改回 `hvc1`（保留 `hvcC` 编码配置，删除 `sinf`）；把 `enca` 改回 `mp4a`（保留 `esds`，删除 `sinf`）。
2. `stbl`：删除 `senc`、`saio`、`saiz` 三个 box。
3. 级联重算所有父 box 大小（`stsd→stbl→minf→mdia→trak→moov`），并修正 `stco/co64` 的 chunk 绝对偏移（若 `moov` 在 `mdat` 之前，删字节后 `mdat` 会前移，偏移要减去删除量）。
4. 对 `mdat` 中每个样本的受保护区间做 AES-128-CTR 解密（子样本级）。

**为什么这样就能播：** CTR 解密不改变样本大小，`stsz/stsc/stts/ctts/stco` 的数值关系不变，只是 `stco` 的绝对偏移整体平移；重封装后得到的是一个**标准明文 HEVC mp4**（`hvc1` + `mp4a`），ExoPlayer 的 `Mp4Extractor` 正常 demux，`MediaCodec` 用 `video/hevc` 硬解。

**HEVC 解码链路：**
- 主：`MediaCodec` 硬解 `video/hevc`（`MIMETYPE_VIDEO_HEVC` 自 API 21 存在；真实 Android TV 盒子基本都有硬解）。
- 兜底软解：media3 的 FFmpeg 扩展 `media3-decoder-ffmpeg` —— **注意：不在 Google Maven**，需自编译（见 §3 风险）。Jellyfin 有预编译 `org.jellyfin.media3:media3-ffmpeg-decoder`（Maven Central，GPLv3），可作备选。

**判定：可行。** 是唯一「不依赖设备 DRM、不依赖第三方 native 播放器、纯 Java 可落地」的完整方案，解密逻辑确定（密钥已由 `HongguoCrypto` 派生并验证）。

---

### 方案 C：FFmpeg/mpv 直连（对齐参考实现「红果鉴」）

**结论：可用，但作为主路径偏重；保留为 Plan C。**

**依据：**
- 参考实现用 media_kit（mpv/FFmpeg）+ `--demuxer-lavf-o=decryption_key=<hex>` 端到端跑通，证明 **FFmpeg mov demuxer 原生支持 CENC 解密**（senc/saiz/saio/tenc + 子样本加密 + 逐样本 IV 全支持），`decryption_key` 直接喂 32-hex 密钥。
- mpv 内建 HEVC 解码：`hwdec=mediacodec` 硬解 + FFmpeg 软解兜底，**天然覆盖无 HEVC 硬解的设备**。
- 集成成本：`io.github.marlboro-advance:mpv-android:1.0.0`（Maven Central，minSdk 21）是第三方 fork，官方 `mpv-android/mpv-android`（`is.xyz.mpv`）需从源码 NDK 编译；AAR ~40MB+/ABI；mpv 为 GPLv2+。JNI + mpv 选项 + 事件回调集成量明显高于 ExoPlayer。

**判定**：若 B 的自研解密链路（mp4 box 重写）在开发中遇到不可接受的返工，或需要最快端到端跑通，C 是「已被实证」的退路。但体积、许可、集成复杂度不符合「最少返工」。

---

### 方案 D：全量下载 + FFmpeg `-decryption_key` 解密后再播

**结论：可作为首版里程碑 / 兜底，但不是边下边播。**

**依据：**
- ffmpeg-kit **主版 minSdk=24**（与 minSdk 21 冲突），但 **LTS 版 minSdk=16**：`com.arthenica:ffmpeg-kit-min:6.0.LTS`（纯解密 remux）或 `com.arthenica:ffmpeg-kit-min-gpl:6.0.LTS`（含 libx264，可转 H264）。
- FFmpeg 内建 `hevc` 解码器（native，必带），`-decryption_key <hex> -i enc.mp4 -c copy clear.mp4` 一行即可产出明文；若要兼容无 HEVC 设备，`-c:v libx264` 转 H264（需 GPL 包）。
- 时长：360p 2.9MB 全量下载约 1~3s，1080p 30MB 约 5~20s（视带宽），解密/remux 秒级。对短剧可接受，但**不支持边下边播**，起播延迟=全量下载时间。

**判定**：满足「最差也要下载+解密后可播」。作为 B 的「最小可行落地」或非 HEVC 设备兜底。

---

## 3. 最终推荐

> **首选 ExoPlayer media3 + 自研 CENC 解密/去封装（方案 B）**：纯 Java 无设备 DRM 依赖、标准 Android 组件、体积小、许可干净（Apache-2.0），CENC-AES-CTR 解密逻辑确定；以「全量下载→解密重封装→播本地明文」先落地，再迭代为流式解密 DataSource 实现边下边播，无 HEVC 硬解设备用 ffmpeg-kit LTS 转 H264 兜底。

**理由（对照目标）：**
- **最稳**：解密不依赖 clearkey CDM（A 的硬伤）、不依赖第三方 native 播放器（C 的集成风险）、密钥已派生验证、CTR 解密大小不变（无二次封装误差）。
- **最少返工**：复用现有 `NetClient.VideoModel`（含 `cencKey/encrypted`）与 `HongguoCrypto.contentKey()`；ExoPlayer 替换 `MediaPlayer` 是标准迁移；CENC 解析器与解密器是独立、可单测的纯 Java 类。
- **seek 支持**：重封装产物是标准 faststart 明文 mp4，ExoPlayer 渐进式 seek 原生支持。
- **设备 HEVC 依赖**：主路径走 MediaCodec 硬解（真实 TV 盒子普遍支持）；软件兜底走 ffmpeg-kit 转 H264，而非需要 NDK 自编译的 media3-ffmpeg。

---

## 4. 实施计划

### 4.1 Gradle 依赖（确切坐标）

```kotlin
// settings.gradle.kts 确保有 google() + mavenCentral()

dependencies {
    // —— 播放内核：media3 ExoPlayer（minSdk 21，compileSdk 需 ≥34，本项目已是 35）
    // media3-exoplayer 会传递引入 common/datasource/extractor/container/decoder
    implementation("androidx.media3:media3-exoplayer:1.4.1")   // 1.3.1~1.5.1 均可
    implementation("androidx.media3:media3-ui:1.4.1")           // 可选：PlayerView（本项目用传统 View 可不用）

    // —— 软件 HEVC 兜底（可选，仅无硬解设备）：ffmpeg-kit LTS，minSdk 16
    // 纯解密 remux 用 min；转 H264 需 min-gpl（含 libx264）
    implementation("com.arthenica:ffmpeg-kit-min-gpl:6.0.LTS")

    // —— Plan C（仅当方案 B 开发受阻才引入）：
    // implementation("io.github.marlboro-advance:mpv-android:1.0.0")  // minSdk 21, Maven Central
}
```

> 说明：**不要**用 `androidx.media3:media3-datasource-okhttp`（其依赖 OkHttp 4.x，与工程现有 OkHttp 3.12.13 冲突）。自研 DataSource 直接复用工程已有 OkHttp 3.12.13 做网络 I/O。

### 4.2 新建/修改的类

**新建（`com.houg.tv.playback` 包）**

| 类名 | 职责 |
|------|------|
| `CencMp4Parser` | 纯 Java 解析 mp4 box：定位 `moov/trak/mdia/minf/stbl`；读 `stsd`（`encv/enca` 及 `hvcC/esds/sinf`）、`stco/co64`、`stsz`、`stsc`、`stts/ctts`；读 `senc`（逐样本 IV+子样本）、`saiz`（各样本 aux 长度）、`saio`（aux 偏移）。 |
| `CencSampleDecryptor` | 纯 Java AES-128-CTR 解密：`SecretKeySpec(byte[16],"AES")` + `Cipher("AES/CTR/NoPadding")` + `IvParameterSpec(iv16)`，按子样本只解密受保护区间的字节流。 |
| `CencMp4Remuxer` | 输入「原始 mp4 字节 + 解密器 + key」，输出「明文 mp4 字节/文件」：解密 mdat、`encv→hvc1`/`enca→mp4a`、删 `senc/saio/saiz`、重算 box 大小与 `stco/co64` 偏移、moov 前置（faststart）。 |
| `CencMp4Downloader` | 用现有 OkHttp（带 `Referer=https://novel.snssdk.com/`）全量下载 mp4 到内存/临时文件，失败按 `urls` 列表依次重试。 |
| `CencDecryptingDataSource`（二期） | 实现 `androidx.media3.datasource.BaseDataSource`：对 ExoPlayer 暴露「明文 mp4」视图，内部按 range 拉密文、实时解密+去封装，实现边下边播（复用 `CencMp4Parser/Remuxer` 逻辑）。 |

**修改**

| 文件 | 改动 |
|------|------|
| `NetClient.java` | **关键**：删除 `videoModel()` 里「跳过 hevc/bytevc2/h265/hev1」的过滤逻辑（当前无 h264 变体，会直接抛「仅剩 H265」异常）。改为保留 HEVC 变体，并把 `VideoModel` 扩展为携带**每个变体的** `codec/height/url/cencKey`（至少保证选出的 HEVC 变体带正确 `cencKey`）。 |
| `PlayerActivity.kt` | 把 `MediaPlayer` 整体替换为 `ExoPlayer`（见 4.3）。 |

### 4.3 PlayerActivity 改造要点

- 字段 `player: MediaPlayer?` → `player: ExoPlayer?`。
- `onCreate`：`ExoPlayer.Builder(this).build()`；`exoPlayer.setVideoSurfaceView(surfaceView)`。
- `playCurrent()`：`videoModel(vid)` 返回后分支：
  - `video.encrypted == true`：后台 `CencMp4Downloader.download(urls, referer)` → `CencMp4Remuxer.remux(bytes, cencKey)` → 写临时明文 mp4 → `player.setMediaItem(MediaItem.fromUri(Uri.fromFile(clearFile)))`。
  - `video.encrypted == false`（罕见）：`MediaItem.fromUri(url)`，配一个带 Referer 头的 `DataSource.Factory`（或直接 URL）。
- 进度/暂停/切集/返回：把 `mp.duration/currentPosition/isPlaying/seekTo/start/pause` 换成 ExoPlayer 对应 API；`releasePlayer()` 换 `player.release()`。UI（SeekBar/控制栏/焦点）逻辑基本保留。
- 清理：`onDestroy` 释放 ExoPlayer 并删除临时明文文件。

### 4.4 CENC 解密关键实现要点

1. **逐样本 IV 构造（8→16 字节规则）**：
   - `tenc.default_Per_Sample_IV_Size = 8`、`default_constant_IV_size = 0`。
   - 16 字节初始计数块 = `perSampleIV[0..7]`（从 `senc` 读）**接** `0x00 × 8`（低 64 位块计数器，从 0 起）。
   - Java 侧：`byte[16] iv = new byte[16]; System.arraycopy(perSampleIV,0,iv,0,8);` → `IvParameterSpec(iv)`。
   - Java `AES/CTR/NoPadding` 的「整 128 位大端自增」与 CENC 的「[IV‖counter] 低 64 位大端自增」在本场景（counter 从 0 起、样本远小于 2^64 块）**逐字节等价**。

2. **子样本加密（subsample encryption）**：
   - 每个样本在 `senc` 中记录 `subsample_count`，然后是若干对 `(clear_bytes, encrypted_bytes)`（16 位；若 `senc` flags 置 0x4 则为 32 位）。
   - 解密规则：对样本字节流，每对先**跳过 clear_bytes 明文**（不消耗 keystream），再对 encrypted_bytes 做 CTR（keystream 跨子样本连续，不重置）。
   - 实现：对每个样本 `cipher.init(ENCRYPT_MODE, key, iv)`；逐子样本 `cipher.update(encryptedRange)`（只喂密文区间），明文区间直接拷贝。CTR 流式特性天然保证「clear 不推进计数器、跨子样本连续」。
   - 音频轨（`enca`）通常是整样本加密（`subsample_count=0` 或单子样本 0/全量），同一套逻辑兼容。

3. **去 CENC 重封装**：
   - `encv → hvc1`（保留 `hvcC`，删 `sinf`）；`enca → mp4a`（保留 `esds`，删 `sinf`）。
   - 删 `stbl` 下的 `senc/saio/saiz`；级联重算父 box size；`stco/co64` 偏移按删除字节量平移；建议把 `moov` 前置（faststart）以支持秒级 seek。
   - CTR 解密不改变样本大小 → `stsz/stsc/stts/ctts` 原样保留。

4. **密钥**：`byte[16] key = video.cencKey`（来自 `HongguoCrypto.contentKey(spade_a)`，QA 样本已验 `c075bd047abf9050204865bd472f81e8`）；KID `6ac3aa95476d5a9162f43e300002ebeb` 仅用于比对，不参与 CTR 运算。

### 4.5 最小验证步骤（怎么确认「真的能播」）

1. **结构确认**：用 Bento4 `mp4dump` 或 `mp4box -info` 看真实 mp4：确认无 `pssh`、有 `encv/enca`、`senc` flags、`saiz/saio`、`perSampleIVSize=8`、`moov` 位置（前/后）、子样本字段是 16 还是 32 位。
2. **密钥金标准（离线）**：`ffmpeg -decryption_key c075bd047abf9050204865bd472f81e8 -i enc.mp4 -c copy clear.mp4`，产出可播明文作为「金标准」。
3. **Java 解密器单测**：`CencMp4Parser` 解析出第一个样本的 IV+子样本，`CencSampleDecryptor` 解密后与 ffmpeg 产出的 `clear.mp4` 对应字节比对（重点验证 8 字节 IV 字节序与子样本 CTR 连续性）。
4. **端到端**：App 内走 `下载→解密→落盘→ExoPlayer 播`，肉眼验证画面/声音/拖动 seek；若首屏花屏，优先怀疑 IV 字节序，用 ffmpeg 金标准快速定位。
5. **边下边播（二期）**：`CencDecryptingDataSource` 接入后，验证起播延迟明显下降、seek 正常。

---

## 5. 风险清单与兜底

| 风险 | 影响 | 兜底 |
|------|------|------|
| 设备无 HEVC 硬解（部分 Android 5.0 老机/低端盒） | 明文 mp4 仍无法解码 | 用 ffmpeg-kit LTS `min-gpl` 转 `-c:v libx264` 成 H264 再播（H264 全设备支持）；或上 media3-ffmpeg/`org.jellyfin.media3` 软解 |
| 软解 `OMX.google.hevc.decoder` 性能不足 | 1080p 软解卡顿 | 优先走硬解；软解只做兜底并限定 360p/720p；检测不到硬解即转 H264 |
| ffmpeg-kit 主版 minSdk=24 冲突 | 直接依赖主版会在 API21 崩溃 | **必须用 `.LTS` 后缀版本**（minSdk 16） |
| media3-ffmpeg 不在 Google Maven、需 NDK 自编译 | 软解兜底引入 Linux/WSL 编译链 | 用 Jellyfin 预编译 `org.jellyfin.media3:media3-ffmpeg-decoder`（GPLv3）或改用 ffmpeg-kit 转码兜底 |
| ClearKey CDM 缺失 / `Mp4Extractor` 无 DrmInitData | 方案 A 彻底不可用 | 已排除 A，走 B |
| 自研 box 重写（`stco` 偏移、box size）出错 | 解密后仍花屏/无法 demux | 用 ffmpeg 产出的明文 mp4 做逐字节比对回归；单测覆盖 box 重写 |
| `moov` 在文件末尾（非 faststart） | 线性播可、seek 需先拉全 moov | 重封装时把 `moov` 前置；文件小，全量下载兜底天然规避 |
| `senc` 子样本字段 32 位（flags 0x4）或含 pattern 加密 | 解析/解密错位 | 解析器按 `senc` flags 支持 16/32 位两套；本片源已确认无 pattern（纯 CTR 子样本） |
| 红果接口 HEVC 变体被 `NetClient` 过滤 | 当前 `videoModel()` 会抛「仅剩 H265」 | 移除 HEVC 过滤并让 `VideoModel` 暴露变体级 `cencKey`（见 4.2） |
| 不同变体密钥不同 | 选错 key 解密失败 | 变体级 `cencKey`；KID 固定时校验 `tenc.default_KID` 匹配 |

---

## 附：一句话结论（回传用）

> 首选 **ExoPlayer media3 + 自研 CENC 解密/去封装 DataSource（方案 B）**，HEVC 走 MediaCodec 硬解，首版「下载→解密→播本地明文」落地、二期流式解密实现边下边播，无 HEVC 硬解设备用 ffmpeg-kit LTS 转 H264 兜底；ClearKey（A）因 ExoPlayer 对非分片 mp4 不读 pssh 而不可行。
