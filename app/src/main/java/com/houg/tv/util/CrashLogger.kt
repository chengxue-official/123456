package com.houg.tv.util

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常记录器。
 *
 * 用户反馈「点进去观看短剧会闪退回选集界面」，但日志里没有任何异常痕迹——
 * 原因是进程级崩溃根本没有被记录过。装上这个 handler 后，任何未捕获的
 * Throwable（含 OOM/StackOverflow 等 Error）都会先落盘到日志文件再交给
 * 系统默认处理，用户下次上传日志即可远程定位。
 *
 * 只做记录，不吞异常：记录完仍调用原 handler 走系统默认流程。
 */
object CrashLogger {

    private const val TAG = "Crash"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // 记录本身绝不能再次抛异常（否则会掩盖原始崩溃）
            }
            if (previous != null && previous !== Thread.getDefaultUncaughtExceptionHandler()) {
                previous.uncaughtException(thread, throwable)
            } else {
                // 没有前序 handler（极少数 ROM）：直接终止，行为等价于系统默认
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun logCrash(context: Context, thread: Thread, t: Throwable) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        AppLog.e(TAG, "未捕获异常 @ ${fmt.format(Date())} thread=${thread.name}\n"
                + "机型=" + Build.MANUFACTURER + " " + Build.MODEL
                + " SDK=" + Build.VERSION.SDK_INT + "\n"
                + sw.toString(), t)
        // 崩溃日志单独存一份，方便用户只传这一份
        try {
            val dir = java.io.File(context.filesDir, "logs").apply { mkdirs() }
            java.io.File(dir, "crash.txt").writeText(
                "时间=" + fmt.format(Date()) + "\n"
                        + "机型=" + Build.MANUFACTURER + " " + Build.MODEL
                        + " SDK=" + Build.VERSION.SDK_INT + "\n\n"
                        + sw.toString()
            )
        } catch (_: Throwable) {
        }
    }
}
