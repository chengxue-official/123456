package com.houg.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.sign.XGorgon
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * 明文片源「边下边播」数据源：ExoPlayer 直接按 Range 拉 CDN，边下解码边播放。
 *
 * 之前起播必须先把整集下载完（CencMp4Downloader 全量 bytes）再解密/写盘，起播时间
 * ≈ 整集下载时间；改成直接流式读取后，起播只等首帧（1~2 秒）。
 * 加密（CENC）片源仍走「整集下载 + 本地解密重封装」（见 PlayerActivity.prepareClearFile），
 * 因为样本级解密不能交给 ExoPlayer 的常规解密链路。
 *
 * 为什么不直接用 media3-datasource-okhttp：它依赖 OkHttp 4.x，本项目为兼容 API 21
 * 固定 OkHttp 3.12.13，二者不兼容；这里手写一层最小实现。
 *
 * 请求头与 CencMp4Downloader 保持一致（App UA + 固定 Referer），否则 CDN 会 403。
 */
class HgStreamDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var connection: Response? = null
    private var input: InputStream? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val start = dataSpec.position
        val requested = dataSpec.length
        val rb = Request.Builder()
            .url(dataSpec.uri.toString())
            .header("User-Agent", XGorgon.USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "*/*")
        if (start > 0 || requested != C.LENGTH_UNSET.toLong()) {
            val end = if (requested == C.LENGTH_UNSET.toLong()) -1L else start + requested - 1
            rb.header("Range", if (end >= 0) "bytes=$start-$end" else "bytes=$start-")
        }
        val resp = HttpClientProvider.get().newCall(rb.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code()
            resp.close()
            throw IOException("HG流媒体 HTTP $code")
        }
        val body = resp.body() ?: run {
            resp.close()
            throw IOException("HG流媒体无响应体")
        }
        // 服务器忽略 Range 直接回 200：必须自己跳过前 start 字节，否则会从头重播
        val skip = if (resp.code() == 200 && start > 0) start else 0L
        var length = body.contentLength()
        if (resp.code() == 206) {
            val total = resp.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull() ?: -1L
            if (total > 0) length = total - start
        } else if (length >= 0) {
            length -= start
        }
        // 上层（Range 读取）指定的长度优先：它比响应体剩余长度更严格
        if (requested != C.LENGTH_UNSET.toLong() && (length < 0 || requested < length)) {
            length = requested
        }
        val stream = body.byteStream()
        try {
            skipFully(stream, skip)
        } catch (e: IOException) {
            resp.close()
            throw e
        }
        connection = resp
        input = stream
        currentUri = dataSpec.uri
        bytesRemaining = if (length >= 0) length else C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val src = input ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }
        val read = src.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            input?.close()
        } catch (_: IOException) {
        } finally {
            input = null
            connection?.close()
            connection = null
            currentUri = null
        }
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(16 * 1024)
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n == -1) throw IOException("HG流媒体跳过 $count 字节失败")
            left -= n
        }
    }

    companion object {
        /** 与 CencMp4Downloader 保持一致：CDN 只认这个 Referer。 */
        private const val REFERER = "https://novel.snssdk.com/"
    }
}

/**
 * 本地缓存文件走 FileDataSource，http(s) 走 [HgStreamDataSource]。
 * 播放器的 MediaSource 工厂从本地文件切到网络地址时用它统一入口。
 */
class HgDataSourceFactory : DataSource.Factory {

    override fun createDataSource(): DataSource = CompositeDataSource()

    private class CompositeDataSource : DataSource {

        private val http = HgStreamDataSource()
        private val file = FileDataSource()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            http.addTransferListener(transferListener)
            file.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            val delegate = if (scheme == "http" || scheme == "https") http else file
            active = delegate
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val delegate = active ?: return C.RESULT_END_OF_INPUT
            return delegate.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = active?.uri

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }
}
