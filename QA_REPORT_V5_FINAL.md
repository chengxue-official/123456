# 红果短剧 TV 版 独立 QA 报告 V5（final-qa 独立复核）

日期：2026-10-09
复核人：final-qa（独立于实现者，fresh eyes）
工程根目录：`D:/hongguo/HougTime`
复核范围：本轮「App 版接口 + X-Gorgon 签名」与「CENC 下载→解密→去封装→ExoPlayer 播放」大改。

---

## 0. 结论摘要

- **构建**：PASS，`clean assembleDebug` BUILD SUCCESSFUL（全量 2m26s）。
- **APK 元数据**：package=`com.houg.tv`，minSdkVersion=`21`，targetSdkVersion=`35`，均符合预期。
- **字节对齐复跑**：PASS，输出 md5 与金标准 `a02a37b8ca8cdba2ca5f6c2c6e1ddd48` **严格相等**；9 个 CENC 残留字节计数全部为 0；stsd fourcc=`hvc1;mp4a`；mdat 起始偏移保持 `159556` 不变。
- **代码审查**：**P0=0，P1=1，P2=5，P3=6**。无阻断性问题，核心「下载→解密→去封装→播放」链路正确。
- **结论**：**达到「可进入真机回归」标准**。P1（未加密内容路径失效）在当前已确认「红果取流 mp4 全部为 HEVC+CENC」的事实下不会在回归中触发，但建议在真机回归后一并修复。

---

## 1. 独立构建

| 项目 | 结果 |
|---|---|
| 命令 | `JAVA_HOME=/c/Program Files/Microsoft/jdk-21.0.9.10-hotspot ./gradlew.bat clean assembleDebug --no-daemon` |
| JDK | OpenJDK 21.0.9 LTS（Microsoft build） |
| 结果 | **BUILD SUCCESSFUL** |
| 全量 clean 耗时 | 2m26s（36 tasks：35 executed, 1 up-to-date） |
| 增量（未 clean）耗时 | 42s |

### 1.1 APK 元数据实测（aapt2 dump badging）

```
package: name='com.houg.tv' versionCode='1' versionName='1.0.0'
minSdkVersion:'21'
targetSdkVersion:'35'
compileSdkVersion='35'
```
- `app/build/outputs/apk/debug/app-debug.apk`（6,429,705 字节）。

### 1.2 构建警告（均非阻断）

1. `AndroidManifest.xml:14` — `android:extractNativeLibs` 不应在 manifest 声明（AGP 建议迁移到 build.gradle）。
2. Kotlin 弃用警告：`EpisodeAdapter.kt:28/30` `getColor` 弃用；`MainActivity.kt:183-191` `SYSTEM_UI_FLAG_*` 弃用。
3. `HttpClientProvider.kt:11` — `context` 参数未使用。
4. Java 编译器：JDK 21 下 `source/target 8` 已弃用（3 条）。

### 1.3 依赖核对（build.gradle.kts）

- `minSdk = 21`、`targetSdk = 35` ✓
- `androidx.media3:media3-exoplayer:1.4.1` ✓
- **无** `media3-datasource-okhttp`（避免与 OkHttp 3.12.13 冲突）✓
- OkHttp `3.12.13`、Glide `4.12.0`、协程 `1.7.3` ✓

---

## 2. 字节对齐独立复跑（关键验证）

未复用实现者的 `_sample_clear_java.mp4`，而是从**当前源码** `CencMp4Parser.java / CencSampleDecryptor.java / CencMp4Remuxer.java` 单独用 `javac` 编译 + 自写独立 box 解析器交叉验证。

复跑入口：`_qa_verify/RemuxVerify.java`（临时 harness，未改工程源码）。
输入：`_sample_enc.mp4`（md5 `293ea4e541f02164b9e034075dbf5a5d`，8,037,964 字节）。
密钥：`c075bd047abf9050204865bd472f81e8`。

| 校验项 | 实测 | 判定 |
|---|---|---|
| 输出 md5 | `a02a37b8ca8cdba2ca5f6c2c6e1ddd48` | **PASS（严格相等）** |
| 输出大小 | 8,037,964（与明文一致） | PASS |
| `encv` 残留 | 0 | PASS |
| `enca` 残留 | 0 | PASS |
| `sinf` 残留 | 0 | PASS |
| `senc` 残留 | 0 | PASS |
| `saio` 残留 | 0 | PASS |
| `saiz` 残留 | 0 | PASS |
| `tenc` 残留 | 0 | PASS |
| `sgpd` 残留 | 0 | PASS |
| `sbgp` 残留 | 0 | PASS |
| stsd fourcc | `hvc1;mp4a;` | PASS |
| 顶层 box 解析 | `ftyp@0+28 moov@28+106648 free@106676+52880 mdat@159556+7878408` | PASS |
| mdat 起始偏移 | `159556`（与金标准一致，free 补齐精确） | PASS |
| 解密耗时 | 约 1.0s（JVM 单线程） | — |

**结论：字节对齐 100% 通过。** remux 的 size 重算、free box 补齐、mdat 偏移保持均正确。

---

## 3. 代码审查问题清单

### P0（阻断）：无

核心链路经独立复跑逐字节对齐，构建通过，无阻断性缺陷。

### P1（应修复）

#### P1-1 未加密内容路径整体失效
- 位置：`PlayerActivity.kt:183`、`PlayerActivity.kt:198-204`
- 问题：
  1. `prepareClearFile` 对 `variants` 非空时的循环里 `if (!v.encrypted) continue`，**跳过所有未加密变体**；若全部变体均为明文 mp4，循环结束后抛「视频下载或解密失败」，即使 URL 完全可用。
  2. 向后兼容分支（`variants` 为空）里无条件调用 `CencMp4Remuxer.remux(enc, video.cencKey)`，而 `video.cencKey` 在未加密时为 `null`，会触发 `trackInfo` 中 `find(...).get(0)` 的 IndexOutOfBounds（无 senc box）或空 key，导致明文 mp4 也无法播放。
- 影响：任何「明文（非 CENC）片源」都无法播放。当前已确认红果取流全为 CENC，**真机回归 happy path 不会触发**，但这是真实的逻辑缺陷（代码显式建模了 `encrypted` 标志与向后兼容分支却均未正确落地明文路径）。
- 建议：未加密时直接下载后原样播放（跳过 remux），`remux` 仅在 `encrypted==true` 时调用。

### P2（建议修复，防御性）

#### P2-1 remux free-box 补齐仅处理 `gap>=8`
- 位置：`CencMp4Remuxer.java:46-47`
- 问题：`int gap = mdatOffset - (moov.start + newMoov.length)`；仅当 `gap>=8` 才用 free 补齐，`gap∈[1,7]` 或 `gap<0` 时 mdat 会偏移，导致 stco/stco64 里的绝对 chunk 偏移失效，产出损坏文件。
- 影响：当前输入（删除 senc/sinf 后 moov 显著缩短，实测 free=52880 字节）不会触发；属理论边界缺陷。
- 建议：对 `gap` 非负且 `<8` 或负值的情况显式抛异常或改用 64 位 mdat 处理，避免静默产出坏文件。

#### P2-2 非常规 CENC（子样本/每样本 saiz）无防御，会静默产出错误明文
- 位置：`CencMp4Parser.java:148-150`（senc，未检查 flags）、`CencMp4Parser.java:163-170`（saiz，忽略每样本大小列表）
- 问题：实现假设「senc flags=0（无子样本）+ saiz default_sample_info_size=8」。若未来片源出现子样本加密或每样本 IV 大小，会按错误方式解密，**不报错**而是产出 md5 不符的垃圾数据。
- 影响：当前已确认无子样本，不触发；属健壮性缺口。
- 建议：读取 senc flags 与 saiz 结构后，对不支持的形态（flags!=0 或 default_size!=8）抛明确异常，避免静默损坏。

#### P2-3 trackInfo 多处 `find(...).get(0)` 无空检查
- 位置：`CencMp4Parser.java:130 / 135 / 148 / 153 / 163 / 173 / 187 / 206`
- 问题：对 stbl/senc/saio/saiz/stsz/stco/stsc 均 `find(...).get(0)`，遇到无 stbl 的 trak（fragment mp4 的 hint 轨、或 moof 结构）会 IndexOutOfBounds。
- 影响：当前红果 flat mp4（moov+mdat）必含 stbl，不触发；防御性建议在缺失时抛带 box 名的异常。

#### P2-4 codec 过滤只排除 bytevc2，未严格「只保留 bytevc1」
- 位置：`NetClient.java:338-341`；关联 `CencMp4Remuxer.java:132-136`（`mapFourcc` 盲映射 `encv→hvc1`）
- 问题：`if (codec.contains("bytevc2")) continue` 会保留一切非 bytevc2 的 codec（含空 codec、潜在的 h264/未知类型）。`encv` 是「加密视频」通用 fourcc，对非 HEVC（如 AVC）加密源盲改 `hvc1` 会产出错误 fourcc 导致解码失败。
- 影响：红果当前确认仅 bytevc1(HEVC)/bytevc2(AV1)，不触发；防御性建议改为显式 `codec=="bytevc1"`，并让 `mapFourcc` 按实际 codec 决定目标 fourcc。

#### P2-5 断点续播（resumePosition）实际失效
- 位置：`PlayerActivity.kt:369-378`（onStop 记录）、`PlayerActivity.kt:246`（switchEpisode 清零）、`PlayerActivity.kt:218-221`（startPlayback seek）
- 问题：`resumePosition` 仅在 `onStop` 写入、仅在 `startPlayback` 消费；但 `switchEpisode` 在调用 `playCurrent()` 前已把它清零，且 `onStop→onStart` 返回前台时没有 onResume 触发 `playCurrent`，也不持久化。因此「集内进度断点续播」不生效，只实现了「集号级别」的追剧历史（`recordHistory`）。
- 影响：真机回归中「切后台再回来」依赖 ExoPlayer 自身保位（通常可续），但进程被杀重建后集内位置丢失。属功能缺口，非崩溃。
- 建议：如需集内续播，应在 `onStop` 将进度写入 `WatchHistory`，并在 `startPlayback` 用该进度 seek；或明确产品口径为「仅集号续播」。

### P3（可选，非阻断）

#### P3-1 快速切集时孤儿 clear_ 文件泄漏
- 位置：`PlayerActivity.kt:151`（cancel 不打断阻塞 IO）、`PlayerActivity.kt:248-249`（cleanupClearFiles）
- 问题：`playJob?.cancel()` 对 `withContext(Dispatchers.IO)` 内的阻塞下载/解密无效（协程取消是协作式的）；旧集任务在 `cleanupClearFiles()` 之后仍可能写盘 `clear_<oldVid>.mp4`，成为孤儿文件。正确性由 `gen` 检查兜底（不会误播），仅磁盘泄漏，下次切集/销毁时清理。
- 建议：可接受；或写盘前再校验一次 `gen`。

#### P3-2 recordHistory 重复调用
- 位置：`PlayerActivity.kt:224`（startPlayback）与 `PlayerActivity.kt:254-255`（onEpisodeComplete）
- 问题：播完自动切集时，上一集先 `recordHistory`（onEpisodeComplete）再 `recordHistory`（下一集 startPlayback），对「当前集」重复记录。幂等、无实际危害。

#### P3-3 后台不暂停
- 位置：`PlayerActivity.kt`（无 onPause 暂停逻辑）
- 问题：切到其它应用/HOME 后未主动 pause，音频可能继续播放（依赖 audio focus 行为，不确定）。建议 onStop 里 `p.pause()`。

#### P3-4 onPlayerError 错误文案泛化
- 位置：`PlayerActivity.kt:72-75`
- 问题：任何 ExoPlayer 错误统一提示「可能不支持 H.265 解码」，对其它错误（渲染失败、surface 异常等）有误导。建议按 `error.errorCode` 细分提示。

#### P3-5 box 偏移用 int 存储，>2GB 文件溢出
- 位置：`CencMp4Parser.java:97`（`boxEnd = i + (int) size`）
- 问题：`size` 为 64 位但强转 int，超大 mdat（>2GB）会溢出。当前片源（单集数百 MB）不触发。

#### P3-6 构建告警
- 位置：见 1.2 节。`extractNativeLibs` 迁移、`source/target 8` 弃用、`HttpClientProvider` 未用参数、`getColor`/`systemUiVisibility` 弃用。均不影响产物正确性。

---

## 4. 真机回归重点清单

1. **HEVC 硬解**：在目标设备（确认支持 HEVC）起播，画面/声音正常；在不支持 HEVC 的设备上应看到明确的「不支持 H.265」提示而非黑屏卡死。
2. **下载→解密→起播**：冷启动到出画面总耗时（下载 8MB 级 + 解密 1s 级 + ExoPlayer 起播），关注首屏等待。
3. **拖动 seek**：SeekBar 拖动到任意位置，音画同步、无花屏；进度条 500ms 刷新是否卡顿。
4. **切集**：上一集/下一集按钮，及播完自动下一集；快速连续切集是否误播旧集（验证 gen 兜底）。
5. **断点续播**：切后台/锁屏/HOME 再返回，进度是否保持；退出后从追剧历史重进，是否落在正确集（当前实现为集号级，非集内位置）。
6. **退出对话框焦点**：选中进度条后按返回 → 「继续播放」→ 确认焦点恢复到播放/暂停按钮，确定键可用。
7. **异常容错**：断网/弱网下的下载失败回退；切换清晰度变体失败后的下一变体重试。

---

## 5. 附：复跑可复现性

```
cd D:/hongguo/HougTime
javac -d _qa_verify/classes \
  _qa_verify/com/houg/tv/playback/CencMp4Parser.java \
  _qa_verify/com/houg/tv/playback/CencSampleDecryptor.java \
  _qa_verify/com/houg/tv/playback/CencMp4Remuxer.java \
  _qa_verify/RemuxVerify.java
java -cp _qa_verify/classes RemuxVerify _sample_enc.mp4 _qa_verify_out.mp4
```
（临时 harness 与产物位于 `_qa_verify/` 与 `_qa_verify_out.mp4`，未改动 `app/src` 工程源码。）
