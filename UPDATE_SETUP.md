# 热更新配置说明

## 整体架构

```
本地代码 ──push──> GitHub (chengxue-official/hongguoTV)
                        │ 联动
                        ▼
                Cloudflare Pages（自动部署）
                        │ 电视访问此域名
                        ▼
                    TV App 检查/下载/安装
```

电视端**只接触 Cloudflare Pages 域名**，不直连 GitHub。
（`raw.githubusercontent.com` 在国内不可达，走 Cloudflare 正好绕开。）

---

## 仓库里的两个关键文件（每次发版只改这俩）

### 1. `update.json` — 版本清单

```json
{
  "versionCode": 1,
  "versionName": "1.0.0",
  "apk": "hongguo-1.0.0.apk",
  "notes": "首个版本"
}
```

| 字段 | 说明 |
|---|---|
| `versionCode` | **整数**，用于比较大小。App 的 versionCode 小于它 → 提示更新 |
| `versionName` | 展示用版本号，如 `1.0.1` |
| `apk` | APK 文件名（相对路径，同 Pages 域名）或完整 URL |
| `notes` | 更新说明，弹窗里展示 |

### 2. `hongguo-<版本>.apk` — 分发包（仓库根目录）

---

## 你还需要做的一步：填 Cloudflare Pages 域名

打开 `app/src/main/java/com/houg/tv/update/UpdateChecker.kt`，把 `HOST` 填成你的 Pages 域名：

```kotlin
const val HOST = "https://你的项目名.pages.dev"
```

> 建议绑定自定义域名（如 `https://tv.你的域名.com`），比 `*.pages.dev` 在国内更稳定。

然后重新构建一次 App 装到电视上即可。

---

## 以后每次发版的流程

**方式一：一键脚本（推荐）**

```bash
release.bat 1.0.1 "修复搜索不到多季短剧的问题"
git add . && git commit -m "release 1.0.1" && git push
```

`release.bat` 会自动：改 `build.gradle.kts` 版本号 → 构建 APK → 复制为 `hongguo-1.0.1.apk`
→ 清掉旧 APK → 重写 `update.json`。

**方式二：手工**

1. 改 `app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`
2. `gradlew :app:assembleDebug`
3. 把 `app/build/outputs/apk/debug/app-debug.apk` 复制到仓库根，改名 `hongguo-<版本>.apk`
4. 改 `update.json` 的 `versionCode`/`versionName`/`apk`
5. `git push`

推送后 Cloudflare Pages 自动部署，电视端下次检查即可看到更新。

---

## 电视端体验

- **启动时**：后台静默检查，**只有真有新版本才弹窗**（已最新/检查失败都静默，不打扰用户）
- **手动**：首页右上角「检查更新」按钮，会明确反馈「已是最新版本」或「发现新版本」

弹窗流程：`发现新版本（展示更新说明）→ 立即更新 → 下载中 → 系统安装器`

---

## 注意事项

| 项 | 说明 |
|---|---|
| APK 大小 | Cloudflare Pages 单文件上限 **25 MiB**，当前 APK 约 10.8 MB，够用 |
| 安装权限 | Android 8.0+ 首次需授权「允许安装未知应用」，App 会引导跳设置 |
| 签名 | debug 签名包可直接覆盖安装；若日后换 release 签名，需同签名才能覆盖升级 |
| 缓存 | App 读 `update.json` 时带了 `Cache-Control: no-cache`，避免拿到旧版本号 |
| 下载完整性 | 先写 `.part` 临时文件，校验字节数后再改名，避免半截包被误装 |
