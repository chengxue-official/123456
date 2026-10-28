package com.houg.tv

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * 强制软解码的 RenderersFactory。
 *
 * 原因：老电视盒子 / 投影仪的 MediaCodec 硬解经常出问题——HEVC 花屏、绿边、
 * `MediaCodec` 初始化失败（`DECODER_INIT_FAILED`），甚至直接黑屏。用户反馈时
 * 光靠"升级视频源"救不了，必须能切到纯软解。
 *
 * 做法：只挂 Audio/Video 两个基于 `MediaCodec` 的渲染器，但把 `MediaCodecSelector`
 * 换成只接受名字带 `"google"`（Android 平台自带的 `c2.android.*` 软解）的选择器，
 * 从而排除 `c2.qcom.*` / `c2.mtk.*` / `OMX.*` 等厂商硬解实现。
 */
class SoftwareRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // 软解不影响扩展/隧道模式，全部关掉，走最普通的解码路径最稳。
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            MediaCodecVideoRenderer(
                context,
                SoftwareMediaCodecSelector,
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: androidx.media3.exoplayer.audio.AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            MediaCodecAudioRenderer(
                context,
                SoftwareMediaCodecSelector,
                eventHandler,
                eventListener,
                audioSink,
            )
        )
    }

    /** 只认软解码器：Android 平台自带的 `c2.android.*` 名字里都含 "google" 或 "android"。 */
    private object SoftwareMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
            )
            val soft = all.filter { info ->
                val name = info.name.lowercase()
                name.startsWith("c2.android.") || name.startsWith("omx.google.") ||
                        name.contains("google") || name.contains("sw.")
            }
            // 万一该 MIME 没有任何软解（极少数老机器），退回全部，避免彻底播不了。
            return if (soft.isNotEmpty()) soft else all
        }
    }
}
