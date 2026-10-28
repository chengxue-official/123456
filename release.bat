@echo off
REM ============================================================
REM  发版脚本：构建 APK -> 复制到仓库根 -> 更新 update.json
REM  用法：  release.bat 1.0.1 "修复搜索不到多季短剧的问题"
REM  之后：  git add . && git commit -m "release 1.0.1" && git push
REM         （Cloudflare Pages 会自动部署）
REM ============================================================
setlocal enabledelayedexpansion

if "%~1"=="" (
  echo 用法: release.bat ^<版本名^> ^<更新说明^>
  echo 示例: release.bat 1.0.1 "修复搜索问题"
  exit /b 1
)

set VERSION_NAME=%~1
set NOTES=%~2
if "%NOTES%"=="" set NOTES=更新到 %VERSION_NAME%

REM --- 版本名 "1.0.1" -> versionCode 101 ---
for /f "tokens=1,2,3 delims=." %%a in ("%VERSION_NAME%") do (
  set /a VERSION_CODE=%%a*10000 + %%b*100 + %%c
)

echo.
echo [1/4] 版本: %VERSION_NAME%  ^(versionCode=!VERSION_CODE!^)
echo [2/4] 写入 build.gradle.kts ...

powershell -NoProfile -Command ^
  "$f='app/build.gradle.kts'; $c=Get-Content $f -Raw -Encoding UTF8; " ^
  "$c=$c -replace 'versionCode\s*=\s*\d+','versionCode = !VERSION_CODE!'; " ^
  "$c=$c -replace 'versionName\s*=\s*\"[^\"]*\"','versionName = \"%VERSION_NAME%\"'; " ^
  "[System.IO.File]::WriteAllText($f,$c,[System.Text.UTF8Encoding]::new($false))"

if errorlevel 1 ( echo 写入版本号失败 & exit /b 1 )

echo [3/4] 构建 APK ...
call gradlew.bat :app:assembleDebug --console=plain
if errorlevel 1 ( echo 构建失败 & exit /b 1 )

set APK_NAME=hongguo-%VERSION_NAME%.apk
echo [4/4] 复制 APK -^> %APK_NAME%
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "%APK_NAME%" >nul
for %%f in (*.apk) do if /i not "%%f"=="%APK_NAME%" del "%%f"

REM --- 写 update.json ---
> update.json echo {
>> update.json echo   "versionCode": !VERSION_CODE!,
>> update.json echo   "versionName": "%VERSION_NAME%",
>> update.json echo   "apk": "%APK_NAME%",
>> update.json echo   "notes": "%NOTES%"
>> update.json echo }

echo.
echo ============ 发版完成 ============
echo   版本: %VERSION_NAME% ^(code=!VERSION_CODE!^)
echo   APK : %APK_NAME%
echo   清单: update.json
echo.
echo 接下来执行:
echo   git add . ^&^& git commit -m "release %VERSION_NAME%" ^&^& git push
echo   Cloudflare Pages 将自动部署
echo ==================================
endlocal
