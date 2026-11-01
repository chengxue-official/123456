package com.houg.tv

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * 硬解 RenderersFactory：默认解码器优先，但剔除「已知会让系统进程 abort」的厂商解码器。
 *
 * 背景（MITV4 / Amlogic 实测，2026-11-01）：`OMX.amlogic.hevc.decoder.awesome`
 * 能正常初始化（265~282ms），约 1 秒后在
 * `ACodec::ExecutingState::onOMXEvent` 里
 * `CHECK_EQ(mCodec->getPortFormat(kPortIndexOutput, outputFormat), OK)` 失败
 * （-2147483646 vs 0）→ SIGABRT，进程直接死。
 *
 * 这是 native abort，Java 层任何兜底都来不及：
 *  - `setEnableDecoderFallback(true)` 只在 `MediaCodec` 抛 Java 异常时换解码器；
 *  - `onPlayerError` 的两级自愈（disableHevc / 切软解）拿不到任何回调。
 * 唯一能在崩溃前生效的手段是「根本不选它」，让它落到同平台其它硬解
 * （如 `OMX.amlogic.hevc.decoder`）或系统软解（`OMX.google.hevc.decoder`）。
 */
class HardwareRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // 仍保留回退：Java 层的初始化/解码失败照旧能换下一个解码器。
        setEnableDecoderFallback(true)
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
                SafeVideoCodecSelector,
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
        )
    }

    companion object {

        /**
         * 已知会 native abort 的解码器：Amlogic 的 `awesome` 系列。
         * 不区分 hevc/avc——两者是同一套实现，且 disableHevc 回退到 H.264 后
         * AVC 的 awesome 同样会踩上。
         */
        fun isKnownCrashing(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("amlogic") && n.contains("awesome")
        }

        /**
         * 过滤解码器候选，并返回被剔除的名字（供日志取证）。
         * 全部被剔除（极少数只挂了 awesome 的机器）时退回原列表——
         * 宁可冒崩溃风险，也不能直接「无解码器」播不了。
         */
        fun filterSafe(infos: List<MediaCodecInfo>): Pair<List<MediaCodecInfo>, List<String>> {
            val bad = infos.filter { isKnownCrashing(it.name) }
            if (bad.isEmpty()) return infos to emptyList()
            val safe = infos.filterNot { isKnownCrashing(it.name) }
            return (if (safe.isEmpty()) infos else safe) to bad.map { it.name }
        }
    }

    private object SafeVideoCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
            )
            return filterSafe(all).first
        }
    }
}
