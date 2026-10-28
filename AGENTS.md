# HougTime 仓库协作约定

## Git 工作流（用户已授权）

- **代码改动自动入库**：功能/修复代码改完并验证通过（编译 `assembleDebug` 退出码 0，或对应的测试/静态检查通过）后，直接 `git add`（按文件名逐个加，不用 `-A`）+ `git commit`，不需要再问。
- **push 仍需明示指令**：用户说 `push` 才推送到 `origin/main`。
- **纯调研/只读输出不产生提交**；未验证通过的改动不入库。
- 提交信息：`fix(scope): 中文摘要` / `feat(scope): ...` / `chore: ...`，正文写清「为什么」（现象、日志证据、决策来源），中文。
- Windows PowerShell 5 **不支持 heredoc**，多行提交信息写到 `$env:TEMP\hg_commit_msg.txt` 再 `git commit -F`，用完删除。
- `docs/qa/` 已被 `.gitignore`，QA 报告只留本地，不入库。
- 发版改动：`app/build.gradle.kts` 的 `versionCode` / `versionName` 与修复一起提交。
