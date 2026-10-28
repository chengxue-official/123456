@echo off
chcp 65001 >nul
echo ============================================
echo   红果短剧 TV 版 - 一键构建（原生 Android）
echo ============================================
echo.

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
set "ANDROID_HOME=D:\dev\tools\android-sdk"

cd /d "D:\hongguo\HougTime"

echo [开始构建] assembleDebug ...
call gradlew.bat assembleDebug

if errorlevel 1 (
    echo.
    echo ============================================
    echo  [构建失败] 请将上方错误信息截图/复制反馈。
    echo ============================================
) else (
    echo.
    echo ============================================
    echo  [构建成功] APK 输出位置：
    echo  D:\hongguo\HougTime\app\build\outputs\apk\debug\app-debug.apk
    echo ============================================
)

pause
