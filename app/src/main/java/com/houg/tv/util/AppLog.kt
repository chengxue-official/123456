package com.houg.tv.util

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志存储：
 * 1. 进程内环形缓冲（上限 [MAX] 条）——日志页展示用，避免低端电视 TextView 渲染过多行；
 * 2. 文件全量持久化（[attach] 后开启）——跨启动保留、自动轮转，上传/分享用。
 * 兼容 API 21：仅 java.io/java.text/java.util。
 */
object AppLog {

    /** 内存缓冲上限（页面展示用） */
    private const val MAX = 200

    /**
     * 单个日志文件字符上限，超过轮转到 applog-old.txt（保留两代）。
     *
     * <p>2026-10-26 从 30k 提到 100k：线上 VIDAA 电视排查「该集未开放免费观看」时，
     * 30k 只够装播放阶段，启动/注册段全被轮掉，设备注册失败的关键报错丢失、只能靠猜。
     * 两代合计 ~200k 字符（中文按 UTF-8 最大 ~500KB），远低于日志上传的 900KB 上限
     * （edge-functions/api/log.js MAX_BYTES），不会撑爆接口。
     */
    private const val FILE_MAX_CHARS = 100_000

    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    /** 接口信息快照（供 LogActivity 展示） */
    @Volatile var baseUrl: String = ""
    @Volatile var deviceId: String = ""
    @Volatile var iid: String = ""
    @Volatile var lastGorgon: String = ""

    private var logDir: File? = null
    private var writer: BufferedWriter? = null
    private var fileChars = 0

    /**
     * 在 Application.onCreate 里调用，开启文件持久化，并写一行启动分隔标记。
     * 必须在第一笔日志之前调用；失败（磁盘异常）时自动降级为纯内存模式。
     */
    fun attach(context: Context) {
        synchronized(lock) {
            try {
                val dir = File(context.filesDir, "logs").apply { mkdirs() }
                logDir = dir
                val cur = File(dir, "applog.txt")
                fileChars = if (cur.exists()) try { readFile(cur).length } catch (e: Exception) { 0 } else 0
                val w = BufferedWriter(FileWriter(cur, true))
                val mark = "\n===== 启动 " + dayFmt.format(Date()) + " =====\n"
                w.write(mark)
                w.flush()
                fileChars += mark.length
                writer = w
            } catch (e: Exception) {
                writer = null
            }
        }
    }

    fun d(tag: String, msg: String) = append("D", tag, msg)

    fun e(tag: String, msg: String, t: Throwable?) {
        val detail = if (t != null) " | " + (t.message ?: t.javaClass.simpleName) else ""
        append("E", tag, msg + detail)
    }

    fun setInterfaceInfo(base: String, dev: String, install: String) {
        baseUrl = base
        deviceId = dev
        iid = install
    }

    /** 内存缓冲（页面展示用）。 */
    fun dump(): String = synchronized(lock) { buffer.joinToString("\n") }

    /**
     * 全量日志：applog-old.txt + applog.txt 拼接（跨启动、含全部轮转内容）。
     * 未 attach 或读文件失败时降级为内存缓冲。
     */
    fun dumpFile(): String {
        val dir = logDir ?: return dump()
        return try {
            val sb = StringBuilder()
            val old = File(dir, "applog-old.txt")
            if (old.exists()) sb.append(readFile(old))
            val cur = File(dir, "applog.txt")
            if (cur.exists()) sb.append(readFile(cur))
            if (sb.isEmpty()) dump() else sb.toString()
        } catch (e: Exception) {
            dump()
        }
    }

    /** 清空内存缓冲与全部日志文件（轮转文件一并删除）。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            closeWriterQuietly()
            val dir = logDir
            if (dir != null) {
                File(dir, "applog-old.txt").delete()
                File(dir, "applog.txt").delete()
            }
            fileChars = 0
            reopenWriter(dir)
        }
    }

    private fun append(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$level] $tag: $msg"
        synchronized(lock) {
            buffer.add(line)
            while (buffer.size > MAX) buffer.pollFirst()
            writeLine(line)
        }
    }

    /** 追加写入文件并按需轮转；磁盘异常静默降级（不影响内存缓冲）。 */
    private fun writeLine(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
            fileChars += line.length + 1
            if (fileChars >= FILE_MAX_CHARS) rotate()
        } catch (e: Exception) {
            // 磁盘异常：关闭写句柄，后续日志仅保留在内存
            closeWriterQuietly()
        }
    }

    /** 轮转：applog.txt → applog-old.txt（覆盖旧 old），新开当前文件。 */
    private fun rotate() {
        closeWriterQuietly()
        val dir = logDir
        if (dir == null) {
            writer = null
            return
        }
        val cur = File(dir, "applog.txt")
        val old = File(dir, "applog-old.txt")
        old.delete()
        if (!cur.renameTo(old)) {
            // 轮转失败（被占用等）：截断当前文件重写，丢历史保可用
            cur.delete()
        }
        fileChars = 0
        reopenWriter(dir)
    }

    private fun reopenWriter(dir: File?) {
        writer = if (dir != null) {
            try {
                BufferedWriter(FileWriter(File(dir, "applog.txt"), true))
            } catch (e: Exception) {
                null
            }
        } else null
    }

    private fun closeWriterQuietly() {
        try {
            writer?.close()
        } catch (e: Exception) {
        }
        writer = null
    }

    private fun readFile(f: File): String = BufferedReader(FileReader(f)).use { it.readText() }
}
