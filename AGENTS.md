# HougTime 仓库协作约定

## 仓库分工（两个独立仓库，勿混）

- **源码仓 = `origin` → `https://github.com/chengxue-official/123456.git`**：全部 App 源码、构建脚本、文档，`git push` 只推这里。
- **热更新仓 → `https://github.com/chengxue-official/hongguoA.git`**：只放热更新发布物（`update.json` 清单 + APK），由 EdgeOne Pages 绑定自动部署到 `https://www.hongguo.kdns.fr`（见 `UpdateChecker.kt`）。发版推包走这个仓，**不要**把源码提交混进来。

## Git 工作流（用户已授权）

- **代码改动自动入库**：功能/修复代码改完并验证通过（编译 `assembleDebug` 退出码 0，或对应的测试/静态检查通过）后，直接 `git add`（按文件名逐个加，不用 `-A`）+ `git commit`，不需要再问。
- **push 仍需明示指令**：用户说 `push` 才推。
- **push = 双推**：用户说 `push` 时既推源码又发更新包——① 源码仓 `git push`（origin/main）；② 在 `d:\hongguo\hongguoTV-release`（hongguoA 仓克隆）跑 `.\release.ps1 -Push`：构建 APK → 生成 `public/update.json`（含 SHA-256，不传 `-Notes` 沿用旧说明）→ git push，EdgeOne Pages 自动部署。发版前版本号按约定递增（当前 2.0.0(128) 之后从 2.0.1(129) 起）。
- **纯调研/只读输出不产生提交**；未验证通过的改动不入库。
- 提交信息：`fix(scope): 中文摘要` / `feat(scope): ...` / `chore: ...`，正文写清「为什么」（现象、日志证据、决策来源），中文。
- Windows PowerShell 5 **不支持 heredoc**，多行提交信息写到 `$env:TEMP\hg_commit_msg.txt` 再 `git commit -F`，用完删除。
- `docs/qa/` 已被 `.gitignore`，QA 报告只留本地，不入库。
- 发版改动：`app/build.gradle.kts` 的 `versionCode` / `versionName` 与修复一起提交。
