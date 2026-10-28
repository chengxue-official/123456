# HougTime — 逆向还原工程

基于 `红果tv简洁版.apk`（包名 `com.houg.tv`，桌面名 Houg时光）的 smali 逆向还原。

## 还原来源

| 混淆类 | 真实语义 | 还原度 |
|---|---|---|
| `L사틀;` | X-Gorgon 签名密钥表 | 100% |
| `L사클;` | 网络核心（27 方法） | 100% |
| `L마을;` | 短剧业务仓库 | 95% |
| `L츄;` | okhttp3.Request.Builder | 100% |
| `L카열;` | 播放进度持久化 | 100% |
| `L라비;` | 封面图缓存下载 | 100% |
| `L너정;` | 业务门面 Facade | 100% |
| `L가난;` | AppContainer（10 字段） | 100% |
| `L라늘;` | 根 Composable + 工具集 | 70% |
| `L가칼;` | 主 UI lambda（51 参数） | 85% |
| `L타먼;` | RecommendViewModel | 95% |
| `L파멸;` | SearchViewModel | 90% |
| `L카쳘;` | PlayerRequestViewModel | 100% |
| `L가넌;` | AppShellViewModel | 100% |
| `L가단;` | 导航枚举（单条目） | 100% |
| `L사글;` | 推荐分区枚举（单条目） | 100% |
| `L카런;` | 编解码器偏好枚举 | 100% |
| `L가건;` | AppShellNavigationState | 100% |
| `L타번;` | RecommendViewState | 100% |
| `L파뇌;` | SavedStateHandle 包装 | 100% |

## 关键成果：X-Gorgon 签名算法

密钥全部硬编码在 `L사틀;-><clinit>`：

- **SALT**（20 字节）：`44b9b9d9a4aef9fca493aa757ca3c2c4a496938f`
- **PREFIX**（6 字节）：`84 04 40 1C 00 00`
- **MAGIC**（4 字节）：`00 06 0B 1C`
- **BITREV**：256 项 8 位倒序表（运行时按位反转生成）

算法：`MD5(query)[0:4] + MD5(body)[0:4] + MAGIC + unixSec(大端)` → 异或 SALT → 高低半字节交换/异或下一字节/查 BITREV/取反异或 0x14 → 前缀 + 大写 HEX。

## 接口清单

```
搜索建议  https://hongguoduanju.com/incent_resource/suggestion?query=<kw>&app_id=8662&count=20
剧集详情  https://hongguoduanju.com/detail?series_id=<id>
播放页    https://hongguoduanju.com/player/<id>/<ep>
视频模型  POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/
```

固定参数：`aid=8662`、`app_name=novelread`、`version_code=73532`、UA 伪装 `com.phoenix.read`。

## 目录结构

```
app/src/main/java/com/houg/tv/
  DuanjuApplication.kt       Application + Coil ImageLoader
  MainActivity.kt            入口，沉浸式全屏 + 自建 ComposeView
  di/AppContainer.kt         依赖容器（10 字段）
  di/JsonProvider.kt         kotlinx Json 单例
  sign/XGorgon.java          签名算法
  sign/SignedRequest.java    请求头装配
  net/NetClient.java         网络层
  net/HttpClientProvider.kt  OkHttp 单例
  repo/DramaRepository.java  业务仓库
  store/PlaybackStore.kt     进度持久化
  store/PlaybackCoordinator.kt
  module/                    SysInfo / List / Cover 三模块
  facade/Facade.kt           门面
  image/CoverDownloader.kt   封面缓存
  ui/Enums.kt                5 个枚举
  ui/State.kt                4 个状态类
  ui/ViewModels.kt           5 个 ViewModel
  ui/RootScreen.kt           根 Composable
  ui/AppContent.kt           主 UI 树
  util/SiteParser.kt          正则表 + 集号挑选
```

## 验证

```bash
# 1) 签名算法自检（Java / Python 应输出一致）
python3 xgorgon.py

# 2) 编译
./gradlew assembleDebug

# 3) 枚举单条目验证（期望输出 1）
#    反编译产物中 L가단;->파 只有一个实例
```

## 已知缺口

Compose 视觉层（`L라늘;->마` 剩余 UI 树、`L가칼;->자` 内部组件链）反编译语义噪声大，已按状态机重建，运行时行为需真机验证。
