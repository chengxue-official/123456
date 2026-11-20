package com.houg.tv.update

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.houg.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新流程 UI：检查中 → 有新版本（展示更新说明）→ 下载中 → 唤起安装。
 * 用 AlertDialog 实现，遥控器确定键/方向键可直接操作。
 */
object UpdateDialog {

    /** 入口：检查更新并走完整流程。 */
    fun start(activity: Activity, scope: CoroutineScope) {
        val current = currentVersionCode(activity)
        val checking = AlertDialog.Builder(activity)
            .setTitle("检查更新")
            .setMessage("正在检查…")
            .setCancelable(true)
            .create()
        checking.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(activity, current) }
            checking.dismiss()

            if (!result.hasUpdate) {
                AlertDialog.Builder(activity)
                    .setTitle("检查更新")
                    .setMessage(result.message)
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }

            val remote = result.remote ?: return@launch
            showAvailable(activity, scope, remote, auto = false)
        }
    }

    /**
     * 启动时自动检查更新：**静默**查一次，只有真有新版本才弹窗。
     *
     * 与 [start] 的区别：
     *  - 不弹「正在检查…」——开机就问用户「检查更新」太吵，而且没更新时不该有任何动静；
     *  - 检查失败完全静默（断网时不能每次启动都甩一个错误框）；
     *  - 「以后再说」会记住版本号并静音 [QUIET_AFTER_DISMISS_MS]，避免每次开 App 都糊脸；
     *    一旦远端出了**更新的版本**（versionCode 变大）立刻重新提示。
     *  - 每个进程只查一次（[launchChecked]），从后台切回前台不重复检查。
     */
    fun checkOnLaunch(activity: Activity, scope: CoroutineScope) {
        if (launchChecked) return
        launchChecked = true
        val current = currentVersionCode(activity)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { UpdateChecker.check(activity, current) }
            } catch (e: Exception) {
                null
            } ?: return@launch
            if (!result.hasUpdate) return@launch
            val remote = result.remote ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (inQuietPeriod(activity, remote.versionCode)) return@launch
            showAvailable(activity, scope, remote, auto = true)
        }
    }

    /** 有新版本时的弹窗：手动入口（auto=false）与启动自动检查（auto=true）共用。 */
    private fun showAvailable(
        activity: Activity,
        scope: CoroutineScope,
        remote: UpdateChecker.Remote,
        auto: Boolean
    ) {
        val notes = remote.notes.ifEmpty { "（无更新说明）" }
        val builder = AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${remote.versionName}")
            .setMessage(
                "当前版本：${currentVersionName(activity)}\n" +
                        "最新版本：v${remote.versionName}\n\n" +
                        "更新内容：\n$notes"
            )
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(activity, scope, remote) }
        builder.setNegativeButton(if (auto) "以后再说" else "稍后") { _, _ ->
            if (auto) rememberDismiss(activity, remote.versionCode)
        }
        val dialog = builder.show()
        // 自动弹窗（开机检查）必须把焦点停在「以后再说」：
        // AlertDialog 默认焦点在正向按钮上，电视遥控器很可能正被按着
        // （方向键/确定），一旦误触就直接开始下 APK，用户会觉得 App 自己乱下载。
        // 手动入口不受影响——那是用户主动点进来的，聚焦「立即更新」才是对的。
        if (auto) {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        }
    }

    /** 该版本是否还在「以后再说」的静音期内。 */
    private fun inQuietPeriod(context: Context, remoteCode: Int): Boolean {
        val p = context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
        if (p.getInt(KEY_DISMISSED_CODE, 0) != remoteCode) return false
        return System.currentTimeMillis() - p.getLong(KEY_DISMISSED_AT, 0L) < QUIET_AFTER_DISMISS_MS
    }

    private fun rememberDismiss(context: Context, remoteCode: Int) {
        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISMISSED_CODE, remoteCode)
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun downloadAndInstall(activity: Activity, scope: CoroutineScope, remote: UpdateChecker.Remote) {
        val needsPermission = Build.VERSION.SDK_INT >= 26 && !canInstallUnknown(activity)

        val progress = AlertDialog.Builder(activity)
            .setTitle("正在下载更新")
            .setMessage("v${remote.versionName} 下载中，请勿关闭电视…")
            .setCancelable(false)
            .create()
        progress.show()

        scope.launch {
            val error = StringBuilder()
            val apk = withContext(Dispatchers.IO) { UpdateChecker.download(activity, remote, error) }
            progress.dismiss()
            if (apk == null) {
                AlertDialog.Builder(activity)
                    .setTitle("更新失败")
                    .setMessage(error.toString())
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }
            // 关键：不再用 canRequestPackageInstalls() 提前拦截。
            // 小米电视等 ROM 上该 API 常年返回 false（即使「允许安装未知应用」已开），
            // 旧逻辑会直接弹「需要授权」并 return，用户被卡死、永远更新不了。
            // 现在直接唤起安装器——系统安装器自己会在需要时弹授权页；
            // 安装器起不来（无 install 权限 / 被 ROM 拦截）时再给分厂商引导。
            val ok = UpdateChecker.install(activity, apk)
            if (!ok && needsPermission) showInstallGuide(activity)
        }
    }

    /** 是否已获「安装未知应用」授权；部分 ROM 该 API 不可靠，仅作提示用，不再作为拦截条件。 */
    private fun canInstallUnknown(context: Context): Boolean = try {
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        true
    }

    /**
     * 无法唤起系统安装器时的兜底引导。
     *
     * 覆盖常见电视 ROM 的开启路径（小米/红米电视为「账号与安全 → 安装未知来源应用」，
     * 且部分系统还有「纯净模式」会二次拦截），并提供直接跳系统的入口。
     */
    private fun showInstallGuide(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("需要开启安装权限")
            .setMessage(
                "请在系统设置中允许「红果TV」安装应用后重试：\n\n" +
                        "· 小米/红米电视：设置 → 账号与安全 → 安装未知来源应用 → 允许\n" +
                        "· 若提示「纯净模式」拦截，请一并关闭纯净模式\n" +
                        "· 其他品牌：设置 → 安全 → 未知来源 / 应用安装权限\n\n" +
                        "设置完成后回到本页重新点击「立即更新」即可。"
            )
            .setPositiveButton("去设置") { _, _ -> openInstallSettings(activity) }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun openInstallSettings(activity: Activity) {
        // 依次尝试：应用级「安装未知应用」页 → 全局安全设置 → 本应用详情页
        val intents = listOf(
            android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:" + activity.packageName)),
            android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            android.content.Intent(Settings.ACTION_SECURITY_SETTINGS),
            android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + activity.packageName))
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // 该 ROM 不支持此页，试下一个
            }
        }
        android.widget.Toast.makeText(
            activity, "请手动到系统设置开启安装权限", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun currentVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        -1
    }

    private fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** 每个进程只自动检查一次：从后台切回前台不重复联网。 */
    @Volatile
    private var launchChecked = false

    /** 「以后再说」对同一版本的静音时长：12 小时（期间开 App 不再打扰）。 */
    private const val QUIET_AFTER_DISMISS_MS = 12 * 60 * 60 * 1000L

    private const val PREF_UPDATE = "houg_update"
    private const val KEY_DISMISSED_CODE = "dismissed_code"
    private const val KEY_DISMISSED_AT = "dismissed_at"
}
