package com.houg.tv

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.Lifecycle
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.net.NetClient
import com.houg.tv.playback.CencMp4Downloader
import com.houg.tv.playback.CencMp4Parser
import com.houg.tv.playback.CencMp4Remuxer
import com.houg.tv.sign.DeviceRegister
import com.houg.tv.util.AppLog
import com.houg.tv.util.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs

/**
 * 播放器：ExoPlayer（media3），下载→CENC 解密去封装→播本地明文 mp4（HEVC hvc1 + AAC）。
 *
 * 交互（遥控器优先，无底部导航栏）：
 *  - OK 键           ：播放 / 暂停
 *  - 左右键          ：快退 / 快进 15 秒；长按按键重复事件会叠加步长（15s→30s→45s…）
 *  - 上 / 下键       ：上一集 / 下一集
 *  - 菜单键          ：调出菜单（画面填充、弹幕开关、倍速、退出）
 *  - 返回键          ：手机单击退出；TV 双击退出（首次提示，2 秒内再按一次退出播放）
 *  - 任意按键        ：短暂显示顶部集信息与底部进度（自动隐藏，不抢焦点）
 */
class PlayerActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var seriesId: String
    private lateinit var title: String
    private lateinit var cover: String
    private var vidList: List<String> = emptyList()
    private var epList: List<Int> = emptyList()
    /** 当前位置下标（0-based）：在 vidList/epList 间移动；显示集号取 epList[位置] */
    private var currentPos = 0
    /**
     * 播放器上当前挂着的 MediaItem 属于哪个位置（-1 = 还没起播过 / 已释放）。
     * 切集时先改 currentPos 再换片，prepare 前播放器里仍是上一集的内容；
     * 此期间读到的进度属于旧集，绝不能写到新集的历史里（会把旧集进度
     * 写成新集的续播点，下次进新集直接被 seek 到旧位置）。
     */
    private var loadedPos = -1
    private var playJob: Job? = null
    private var playGen = 0

    private var player: ExoPlayer? = null
    private var tracking = false
    /** 拖动进度条时预览的目标位置（ms）；0 表示无预览。松手后才真正 seek。 */
    private var seekBarPreviewMs = 0L
    /** 拖动进度条前是否处于播放态：松手后据此决定要不要自动恢复播放。 */
    private var wasPlayingBeforeSeek = false
    /** 切后台前记录的播放进度（ms），恢复时 seek 到该位置 */
    private var resumePosition = 0

    /** 视频真实宽高（onVideoSizeChanged 回调，未解析前为 0） */
    private var videoWidth = 0
    private var videoHeight = 0
    /** 从 mp4 tkhd 解析的显示宽高与旋转角（解密/缓存命中后设置，优先于 ExoPlayer 回调） */
    private var tkhdWidth = 0
    private var tkhdHeight = 0
    private var tkhdRotation = 0
    /**
     * 应播放状态：区分「用户主动暂停」与「起播过程中被切到后台」。
     * 起播完成时若已不可见则不应 auto-play；回前台时据此恢复，避免把用户
     * 手动暂停的播放强行续起。
     */
    private var shouldBePlaying = true
    /** 当前画幅模式下标，见 ASPECT_MODES */
    private var aspectMode = 0
    /**
     * 手机端「翻转」：true = 横屏播放（横版画幅短剧），false = 竖屏（默认）。
     * 落盘 player_prefs，下次进播放页沿用；TV 端不提供该项（电视本就横屏）。
     */
    private var landscapeOn = false
    /**
     * 当前清晰度档位下标，见 QUALITY_MODES。
     * 「自动」沿用设备能力判定（低端设备压 720），其余档位由用户显式指定上限。
     */
    private var qualityIndex = 0
    /** 本集实际取到的清晰度高度（videoModel 返回），0 表示未知（如命中本地缓存）。 */
    private var currentVariantHeight = 0
    /** 本集实际取到的编码（videoModel 返回，如 bytevc1/avc），空串表示未知（如命中本地缓存）。 */
    private var currentVariantCodec = ""
    /**
     * 本集服务端实际下发的可用清晰度高度集合（已剔除 bytevc2 与设备解不了的编码）。
     * 空集表示未知（命中本地缓存 / 还没取流），此时清晰度菜单不设限。
     */
    private var availableHeights: Set<Int> = emptySet()
    /** 用户刚切过清晰度：下次 STATE_READY 提示一次实际生效档位（服务端可能无该档而回退）。 */
    private var pendingQualityHint = false

    /** 本集起播时是否已经做过「续播 seek」（避免重复 seek 打断用户手动拖动） */
    private var resumedThisEpisode = false
    /**
     * 待执行的续播位置（ms）。
     *
     * ⚠️ 续播 seek 必须等 STATE_READY（duration 已知）再执行：prepare() 未完成时
     * duration 返回 C.TIME_UNSET（大负数），此时无法判断「上次位置是否已接近片尾」。
     * 旧实现在这里直接 seek，如果上次停在片尾附近（每集播完都会把片尾位置写进历史），
     * seek 过去立刻抛 STATE_ENDED → 被判"播完"自动跳下一集 → 下一集同样带着片尾
     * 续播点 → 连环跳集（用户反馈的恶性 bug）。
     */
    private var pendingResumeMs = 0L
    /** 正在切集：用于抑制切集瞬间的虚假 STATE_ENDED 回调 */
    private var switchingEpisode = false
    /** 本集起播是否已失败（解码/网络）。失败时上下键仍可切走，但不再自动跳集。 */
    private var currentEpisodeFailed = false
    /**
     * 因「本地缓存文件损坏」已自动删档重下过的 vid。
     * 每个 vid 只自愈一次，避免文件反复下坏时陷入「下载→失败→再下载」死循环。
     */
    private var ioRetriedVid: String? = null
    /**
     * 因「解码器初始化失败」已自动切软解重试过的 vid。
     * 每个 vid 只自动切一次——真不支持时（片源只有 HEVC 且软解也扛不住）
     * 反复重建播放器只会让用户看到无限闪烁。
     */
    private var autoSoftFailoverVid: String? = null
    /**
     * 因「解码失败」已自动排除 HEVC 重取流过的 vid（第一级自愈）。
     * 升级链：硬解失败 → 先删缓存+disableHevc 重新取 H.264（不动解码方式）；
     * 若仍失败 → 切软解重试一次；再失败 → 报错终态。
     */
    private var autoHevcSkipVid: String? = null
    /**
     * 切集抑制的安全兜底：STATE_READY / onPlayerError 理论上都会解除抑制，
     * 但为了防任何未知的卡死路径（如某些 ROM 回调丢失），切集时同时挂一个
     * 延迟任务，到点强制解除——上下键永不能被锁死。
     */
    private val switchGuardRunnable = Runnable {
        if (switchingEpisode) {
            AppLog.d("PlayerActivity", "切集抑制超时（兜底解除）")
            switchingEpisode = false
        }
    }
    /** 上次写观看历史的时间戳（节流，避免 500ms 一次写盘） */
    private var lastHistoryWrite = 0L
    /** 上次按返回键的时间戳（双击退出判定） */
    private var lastBackPressed = 0L

    // ===== 手机端上下滑切集（抖音式） =====
    /** 本次手势按下点（px，相对 Activity 根视图） */
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    /** 本次手势是否已判定为纵向滑动（够长且明显压过横向） */
    private var swipeCandidate = false

    private lateinit var videoContainer: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: View
    private lateinit var controlBar: View
    private lateinit var menuPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var episodeInfo: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var toastHint: TextView
    private lateinit var menuFill: TextView
    private lateinit var menuRotate: TextView
    private lateinit var menuQuality: TextView
    private lateinit var menuDanmaku: TextView
    private lateinit var menuSpeed: TextView
    private lateinit var menuEpisodes: TextView
    private lateinit var menuDecoder: TextView
    private lateinit var menuExit: TextView
    private lateinit var btnMenu: TextView
    private lateinit var progress: View
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    /** 失败提示上的「重试」：实际是换新设备身份后重播（见 retryWithNewIdentity）。 */
    private lateinit var errorRetry: TextView
    /** 在途的重试任务：防止用户连点重复换身份（BUG-114）。 */
    private var retryIdentityJob: Job? = null
    private lateinit var danmakuView: DanmakuView

    // ===== 选集弹窗 =====
    private lateinit var epPickerRoot: View
    private lateinit var epPickerTitle: TextView
    private lateinit var epPickerRange: TextView
    private lateinit var epGrid: GridLayout
    private lateinit var epBtnClose: TextView
    private lateinit var epBtnJump: TextView
    private lateinit var epBtnPrev: TextView
    private lateinit var epBtnNext: TextView
    /** 弹窗当前页（组）起始集号（0-based，页内第一集） */
    private var epGroupStart = 0
    /** 弹窗内的格子视图缓存（每页 20 个），复用避免反复 new */
    private val epCells = ArrayList<TextView>()

    private var speedIndex = 2
    private var danmakuEnabled = true

    /** 快进基础步长（设置页可选 10/15/30/60s，默认 15s；长按重复时按此递增） */
    private var seekStepMs = QUICK_SEEK_MS

    /** 播完自动连播（设置页可关；关闭后播完停在最后一帧由用户手动切集） */
    private var autoNext = true
    /**
     * 解码方式：true=软解（强制走 Audio/Video 软解码器），false=硬解（默认，交给系统 MediaCodec）。
     * 老盒子/投影仪上硬解 HEVC 常出现花屏、绿边、解码器初始化失败，用户可在播放菜单里切软解救急。
     */
    private var decoderSoft = false
    private val danmakuFetchedWindows = mutableSetOf<Long>()
    private var lastDanmakuWindow = -1L
    /**
     * 弹幕窗口拉取失败次数与下次重试时间（D-08：失败窗口带退避重试）。
     * 旧实现失败后只记日志，要等播放跨入下一个 30s 窗口才会再试该窗口——
     * 网络抖动一次，这 30 秒的弹幕就永远丢了。这里 5s 退避、最多 3 次。
     */
    private val danmakuAttempts = mutableMapOf<Long, Int>()
    private val danmakuRetryAt = mutableMapOf<Long, Long>()
    /**
     * 预加载：串行预取协程句柄 + 运行标记。
     * 从「只预取下一集」升级为「预取当前集之后 PREFETCH_AHEAD 集」，
     * 实现任意方向切集（含选集弹窗跳转）都丝滑命中缓存。
     * "是否已预取" 以本地缓存文件实际存在为准（自校正，不依赖内存集合，
     * 避免清理缓存后内存标记变脏导致漏取）。
     */
    private var prefetchJob: Job? = null
    @Volatile private var prefetchRunning = false
    /** 本轮预取失败的 vid：不再反复重试，避免请求风暴被风控（切集后新窗口自然重来）。 */
    private val failedPrefetch = mutableSetOf<String>()
    /**
     * 媒体 CDN 限流（HTTP 429）冷却截止时间。
     * 线上 1.1.3 MiTV 日志：预加载撞 429 后仍逐集推进，连环请求把限流窗口一直续着，
     * 用户切集时全部失败只能杀进程。命中 429 后暂停预加载到该时间点。
     */
    @Volatile private var mediaCooldownUntil = 0L
    @Volatile private var mediaCooldownActive = false
    /**
     * 当前错误面板是否由媒体限流（429）引起：此时「重试」只重播、不换设备身份——
     * 429 与身份无关，换身份反而在限流窗口内多发一次注册请求（QA BUG-005）。
     * 每次 playCurrent 入口复位。
     */
    @Volatile private var retryLimited429 = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                // 真正就绪：此刻起才是可信的播放状态，解除切集抑制。
                // 不能提前在 startPlayback() 末尾复位——prepare() 是异步的，
                // 解码器初始化期的 STATE_ENDED 会在那之后才投递过来，抑制窗口太短会漏。
                Player.STATE_READY -> {
                    releaseSwitchGuard()
                    // 解码就绪：转圈可以收了（首帧通常紧随其后；这里兜住个别
                    // 机型首帧回调缺失的情况，避免转圈盖在画面上）
                    hideLoading()
                    // 参考 HGTV MainActivityPlayer：STATE_READY 时必须确认视频轨真的被选中。
                    // 有些资源只解出音频轨（如 bytevc2/AV1 那类容器里没可用视频 track），
                    // 此时 ExoPlayer 照样进 READY，用户看到的是「有声音的黑屏」——
                    // 这比直接报错更糟，因为进度条一直在走，看起来像正常播放。
                    if (hasVideoTrack()) {
                        // 【检验环节4】READY 且视频轨确认被选中，记录实际解码到的格式
                        logReadyVideoTrack()
                        applyPendingResume()
                        notifyQualityIfNeeded()
                        // 预加载提前到「本集就绪」：当前集播的是本地明文文件，CDN
                        // 带宽全程空闲；越早把后面几集拉进缓存，用户中途划走时越可能
                        // 命中缓存——这是「无感切集」的前提。后台起播（用户已按主页）
                        // 不预取，避免后台跑流量。
                        if (isVisibleNow()) prefetchNext()
                    } else {
                        onNoVideoTrack()
                    }
                }
                Player.STATE_ENDED -> onEpisodeComplete()
            }
        }

        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
            // tkhd 解析结果优先（解密时已拿到，含旋转元数据）；无 tkhd 时回退 ExoPlayer 回调
            if (tkhdWidth > 0 && tkhdHeight > 0) {
                if (tkhdRotation == 90 || tkhdRotation == 270) {
                    // 竖屏旋转流：tkhd 原始宽高是横向的（如 1920x1080），显示时需互换
                    videoWidth = tkhdHeight
                    videoHeight = tkhdWidth
                } else {
                    videoWidth = tkhdWidth
                    videoHeight = tkhdHeight
                }
            } else {
                videoWidth = size.width
                videoHeight = size.height
            }
            applyAspectRatio()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 边栏只在「暂停」与「拖动进度条」时常驻；恢复播放立即收起。
            if (isPlaying) {
                handler.removeCallbacks(hideControlsRunnable)
                if (!tracking) {
                    topBar.visibility = View.GONE
                    controlBar.visibility = View.GONE
                }
            } else if (!switchingEpisode
                    && player?.playbackState == Player.STATE_READY) {
                // 只有「真正就绪且处于暂停」才算用户暂停；起播/切集的缓冲态
                // （IDLE/BUFFERING）即便 isPlaying=false 也不弹边栏。
                showControls()
            }
        }

        override fun onRenderedFirstFrame() {
            // 真正出画面的那一刻才收起转圈（无感切集的核心：有画面就不该有 loading）
            hideLoading()
        }

        override fun onPlayerError(error: PlaybackException) {
            hideLoading()
            // ⚠️ 解码失败的终态是 STATE_IDLE，永远不会再进 STATE_READY。
            // 若不复位切换抑制标记，switchingEpisode 会永久为 true，
            // 用户按上下键切集将被永久拦截（只有退出播放页才能恢复）。
            currentEpisodeFailed = true
            releaseSwitchGuard()

            // ===== 错误分类：I/O 类不是解码问题，别误导用户换设备 =====
            // 线上反馈：VIDAA_TV 报「当前设备可能不支持 H.265 解码
            // (ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)」，但该码属于
            // androidx.media3 的 **IO 分支**（读本地文件越界），与解码器无关。
            // 真因是明文缓存文件损坏/被截断（下载中途进程被杀、磁盘写满等），
            // 而 findCachedClearFile 只看"存在且 >1KB"就会命中坏文件 →
            // 每次进这一集都失败，看起来像"设备不支持"，其实删掉重下即可。
            if (isLocalFileCorruption(error)) {
                val badVid = vidList.getOrNull(currentPos)
                if (badVid != null && badVid != ioRetriedVid) {
                    ioRetriedVid = badVid
                    AppLog.d("PlayerActivity", "疑似本地缓存损坏(" + error.errorCodeName
                            + ")，删除重下 vid=$badVid")
                    try {
                        clearFile(badVid).delete()
                    } catch (e: Throwable) {
                        AppLog.d("PlayerActivity", "删除坏缓存失败: " + (e.message ?: ""))
                    }
                    // 删完直接重新起播本集（playCurrent 会走完整取流+下载+解密路径）。
                    // 每个 vid 只自动重试一次，避免反复失败时陷入下载循环。
                    currentEpisodeFailed = false
                    playCurrent()
                    return
                }
                showError("视频文件异常，请检查设备存储空间后重试（" + error.errorCodeName + "）")
                return
            }

            // ===== 解码失败自愈（有界两级升级）=====
            // 线上日志（DWIN-380P / 华为 Android 12）：HG下发的 1080p 变体是
            // bytevc1(HEVC)，该机 HEVC 硬解初始化失败 → 黑屏。
            // videoModel 已优先给「探测不支持 HEVC」的设备选 H.264，但仍兜不住两点：
            // ① 探测误判（ROM 谎报支持）；② 片源只有 HEVC 一档。
            //
            // 旧实现只有「切软解」一级，且不删缓存 —— rebuildPlayer→playCurrent
            // 会命中同一份 HEVC 缓存文件，软解面对同一码流同样失败，于是
            // 「重建→失败→再重建」原地打转（日志 18:39:16~18:39:18 两秒内往返 4 次）。
            // 现在改为两级，每级都先删缓存、且只在本 vid 上升级一次：
            //   一级：排掉 HEVC + 删缓存 + 保持硬解重取流（多半是选流错，H.264 硬解即可）
            //   二级：一级仍失败 → 切软解再试一次
            //   终态：两级都用尽仍失败 → disableHevc 并报可读错误，不再重建
            if (isDecodeFailure(error)) {
                val vid = vidList.getOrNull(currentPos)
                // 无论走到哪一级，先把 HEVC 否决掉并清掉这份坏缓存：
                // 缓存里就是解不动的码流，留着只会让下次重试继续命中它。
                if (vid != null) {
                    // BUG-103：只删当前档位不够——disableHevc 之后若命中另一档位
                    // （用户切换过清晰度）遗漏的 HEVC 缓存，仍会原样再失败一次。
                    deleteAllCachedClearFiles(vid)
                }
                try {
                    NetClient.disableHevc()
                } catch (e: Throwable) {
                    AppLog.d("PlayerActivity", "disableHevc 失败: " + (e.message ?: ""))
                }

                // 一级：仍用硬解，但重取流（此时 NetClient 只给 H.264）
                if (vid != null && autoHevcSkipVid != vid) {
                    autoHevcSkipVid = vid
                    AppLog.d("PlayerActivity", "解码失败(" + error.errorCodeName
                            + ")，排除 HEVC 后重取流重试 vid=" + vid)
                    toast(getString(R.string.decoder_retry_avc))
                    currentEpisodeFailed = false
                    replayCurrentEpisode()
                    return
                }
                // 二级：切软解重试一次
                if (vid != null && autoSoftFailoverVid != vid && !decoderSoft) {
                    // 守卫（B9）：低内存设备软解高分辨率 HEVC 预期
                    // OutOfMemoryError（线上 1994MB 的 VIDAA 电视实测），不再白切一次，
                    // 直接给可读错误终态。
                    // 线上 6560MB 的 DWIN-380P 软解 1924p 能 READY（日志 18:58:31）——
                    // 守卫按内存放宽：总内存 ≥3GB 允许高分辨率软解自救，播着卡也比报错强；
                    // 读不到内存（-1）仍按低内存保守拒绝。低档位（≤480p）始终允许自救。
                    if (currentVariantCodec.contains("bytevc1") && currentVariantHeight > 480 && !softDecodeViable()) {
                        AppLog.e("PlayerActivity", "HEVC " + currentVariantHeight
                                + "p 软解预期 OOM（低内存设备，已重取流仍下发 HEVC），跳过软解自救 vid=" + vid, null)
                        showError(getString(R.string.decoder_hevc_soft_oom))
                        return
                    }
                    autoSoftFailoverVid = vid
                    AppLog.d("PlayerActivity", "解码失败(" + error.errorCodeName
                            + ")，自动切软解重试 vid=" + vid)
                    // 不把自动切换落盘：这是「本机这一集」的临时自救，不该改变
                    // 用户在其他剧集上的默认解码偏好（手动切换才落盘）。
                    decoderSoft = true
                    updateDecoderLabel()
                    toast(getString(R.string.decoder_auto_soft))
                    currentEpisodeFailed = false
                    rebuildPlayer()
                    return
                }
            }
            showError(describePlaybackError(error))
        }
    }

    /** 是否解码器类失败（初始化/查询/解码），可由「切软解」尝试救回。 */
    private fun isDecodeFailure(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> true
            else -> false
        }
    }

    /**
     * 高分辨率软解自救是否值得尝试：总内存 ≥3GB（线上 6560MB 的 DWIN-380P
     * 软解 1924p HEVC 能 READY，日志 18:58:31）；读不到内存（-1）时保守拒绝。
     * 阈值依据：1994MB 的 VIDAA 电视软解 1080p 实测 OOM，3769MB 的小米电视余量充足。
     */
    private fun softDecodeViable(): Boolean {
        return DeviceInfo.totalMemoryMb() >= 3 * 1024
    }

    /**
     * 是否属于「本地明文文件损坏/读取越界」类错误（可通过删缓存重下自愈）。
     * 只认 IO / 容器解析分支；解码器分支交给 describePlaybackError 报 H.265。
     */
    private fun isLocalFileCorruption(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> true
            else -> false
        }
    }

    /** 播放错误 → 用户可读文案（不再把所有错误都甩锅给 H.265）。 */
    private fun describePlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "当前设备可能不支持 H.265 解码（" + error.errorCodeName + "）"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "网络连接中断，请检查网络后重试（" + error.errorCodeName + "）"
            else -> "播放失败（" + error.errorCodeName + "）"
        }
    }

    /** 解除切集抑制（同时撤掉兜底任务）。 */
    private fun releaseSwitchGuard() {
        handler.removeCallbacks(switchGuardRunnable)
        switchingEpisode = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放页同样全屏沉浸：手机竖屏刷短剧时不留状态栏/导航栏
        UiScale.applyImmersive(this)
        setContentView(R.layout.activity_player)
        // 手机触摸端：菜单项/选集按钮降级为普通可点击，避免"第一次点击只聚焦、要再点一次"
        UiScale.softenTouchFocus(findViewById(android.R.id.content))
        // 手机端触控目标：44dp 菜单/选集按钮抬到 48dp（TV 不生效）
        UiScale.enforceTouchTargets(findViewById(android.R.id.content))

        // ===== 保活（用户反馈：看着看着自动进入屏保/电视待机）=====
        // HQQ1 Max 等低内存盒子（总内存 823MB）上，一旦屏保/待机把本 Activity
        // 切到后台，系统立即回收进程（日志证实 19:03:08→19:03:25 进程被杀重启）。
        // ① 窗口常亮 FLAG_KEEP_SCREEN_ON：播放期间系统屏保永不触发（治本）。
        //    该 Flag 只在本窗口前台有效，退出播放页/熄屏由系统自动失效，无需手动管理。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        vidList = intent.getStringArrayListExtra(EXTRA_VID_LIST) ?: emptyList()
        epList = intent.getIntegerArrayListExtra(EXTRA_EP_LIST) ?: emptyList()
        currentPos = intent.getIntExtra(EXTRA_EPISODE_POS, 0)
            .coerceIn(0, (vidList.size - 1).coerceAtLeast(0))

        surfaceView = findViewById(R.id.video_surface)
        videoContainer = findViewById(R.id.video_container)
        topBar = findViewById(R.id.top_bar)
        controlBar = findViewById(R.id.control_bar)
        menuPanel = findViewById(R.id.menu_panel)
        seekBar = findViewById(R.id.seek_bar)
        episodeInfo = findViewById(R.id.episode_info)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        toastHint = findViewById(R.id.toast_hint)
        menuFill = findViewById(R.id.menu_fill)
        menuRotate = findViewById(R.id.menu_rotate)
        menuQuality = findViewById(R.id.menu_quality)
        menuDanmaku = findViewById(R.id.menu_danmaku)
        menuSpeed = findViewById(R.id.menu_speed)
        menuEpisodes = findViewById(R.id.menu_episodes)
        menuDecoder = findViewById(R.id.menu_decoder)
        menuExit = findViewById(R.id.menu_exit)
        btnMenu = findViewById(R.id.btn_menu)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        errorRetry = findViewById(R.id.btn_error_retry)
        errorRetry.setOnClickListener { retryWithNewIdentity() }
        danmakuView = findViewById(R.id.danmaku_view)

        epPickerRoot = findViewById(R.id.ep_picker_root)
        epPickerTitle = findViewById(R.id.ep_picker_title)
        epPickerRange = findViewById(R.id.ep_picker_range)
        epGrid = findViewById(R.id.ep_grid)
        epBtnClose = findViewById(R.id.ep_btn_close)
        epBtnJump = findViewById(R.id.ep_btn_jump)
        epBtnPrev = findViewById(R.id.ep_btn_prev)
        epBtnNext = findViewById(R.id.ep_btn_next)

        // 手机竖屏：560dp 选集面板会溢出屏宽，改满宽 + 16dp 边距（TV 保持 560dp 右浮）
        if (!UiScale.isTv(this)) {
            val epPanel = findViewById<View>(R.id.ep_picker_panel)
            val lp = epPanel.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            val m = (16 * resources.displayMetrics.density).toInt()
            lp.marginStart = m
            lp.marginEnd = m
            epPanel.layoutParams = lp
            // 5 列 × 90dp 在 411dp 屏上会被裁掉两列，手机改 4 列等宽填充
            epGrid.columnCount = 4
        }

        // 读取持久化的画幅模式（默认「适应」= 保持比例）、倍速、弹幕开关
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        aspectMode = prefs.getInt(PREF_ASPECT, ASPECT_FIT)
            .coerceIn(0, ASPECT_MODES.size - 1)
        speedIndex = prefs.getInt(PREF_SPEED, 2).coerceIn(0, SPEEDS.size - 1)
        danmakuEnabled = prefs.getBoolean(PREF_DANMAKU, true)
        decoderSoft = prefs.getBoolean(PREF_DECODER_SOFT, false)
        qualityIndex = prefs.getInt(PREF_QUALITY, QUALITY_AUTO)
            .coerceIn(0, QUALITY_MODES.size - 1)
        seekStepMs = prefs.getLong(PREF_SEEK_STEP, QUICK_SEEK_MS)
        autoNext = prefs.getBoolean(PREF_AUTO_NEXT, true)
        // 翻转：沿用上次在播放页选的横/竖屏（横版画幅短剧可直接以横屏进入）
        landscapeOn = prefs.getBoolean(PREF_LANDSCAPE, false)
        if (landscapeOn && !UiScale.isTv(this)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        player = buildPlayer()
        player!!.setVideoSurfaceView(surfaceView)
        player!!.setAudioAttributes(AudioAttributes.DEFAULT, /*handleAudioFocus=*/true)
        // ② 播放期间持有 CPU + Wi-Fi 唤醒锁：即便被系统熄屏/待机，缓冲与网络
        //    请求也不会因 CPU 挂起而中断，回前台可无缝续播（配合 FLAG_KEEP_SCREEN_ON，
        //    正常情况下根本不会走到这一步）。需 AndroidManifest 声明 WAKE_LOCK（已有）。
        try {
            player!!.setWakeMode(C.WAKE_MODE_NETWORK)
        } catch (e: Exception) {
            AppLog.d("PlayerActivity", "setWakeMode 失败: " + (e.message ?: ""))
        }
        player!!.addListener(playerListener)

        updateAspectLabel()
        updateRotateLabel()
        updateQualityLabel()
        updateSpeedLabel()
        updateDanmakuLabel()
        updateDecoderLabel()
        applyDanmakuVisibility()
        updateEpisodeInfo()
        bindControls()

        // 起播时不显示边栏（用户诉求：只有暂停或拖进度条才显示）。
        // 顶栏集信息仍会随「切集/暂停」出现，起播画面保持干净。
        topBar.visibility = View.GONE
        controlBar.visibility = View.GONE
        // 初始焦点交给隐形锚点：播放页不需要焦点导航，全部按键由 onKeyDown 处理。
        // 若不指定，TV 可能把焦点给到某个可聚焦控件（历史版本曾落在 SeekBar 上，
        // 导致左右键被 SeekBar 吃掉、快进快退失效）。
        findViewById<View>(R.id.focus_anchor).post {
            try { findViewById<View>(R.id.focus_anchor).requestFocus() } catch (_: Exception) {}
        }
        handler.post(progressRunnable)
        playCurrent()
    }

    private fun bindControls() {
        menuFill.setOnClickListener { cycleAspect() }
        menuRotate.setOnClickListener { toggleRotate() }
        // 翻转只对手机有意义（电视固定横屏摆放），TV 端直接隐藏该项
        if (UiScale.isTv(this)) menuRotate.visibility = View.GONE
        menuQuality.setOnClickListener { cycleQuality() }
        menuDanmaku.setOnClickListener { toggleDanmaku() }
        menuSpeed.setOnClickListener { cycleSpeed() }
        menuEpisodes.setOnClickListener { openEpisodePicker() }
        menuDecoder.setOnClickListener { cycleDecoder() }
        menuExit.setOnClickListener { showExitDialog() }

        // 手机没有菜单键，用底部控制栏的「菜单」按钮代替（TV 仍走 KEYCODE_MENU）
        btnMenu.setOnClickListener { toggleMenu() }
        if (!UiScale.isTv(this)) btnMenu.visibility = View.VISIBLE

        epBtnClose.setOnClickListener { closeEpisodePicker() }
        epBtnJump.setOnClickListener { showJumpDialog() }
        epBtnPrev.setOnClickListener { switchEpGroup(-1) }
        epBtnNext.setOnClickListener { switchEpGroup(1) }

        surfaceView.setOnClickListener {
            // 手机端没有菜单键：菜单展开时点画面先收起菜单，其余情况维持点按暂停
            when {
                menuPanel.visibility == View.VISIBLE -> hideMenu()
                // 失败提示带「重试」时，点画面/按确定键 = 换新身份重播。
                // 注意：遥控器确定键会先被这个 click 监听消费（不会走到 onKeyDown），
                // 所以这里必须优先判定错误态，否则 OK 键会被当成「暂停/继续」。
                errorPanel.visibility == View.VISIBLE && errorRetry.visibility == View.VISIBLE ->
                    retryWithNewIdentity()
                else -> togglePlayPause()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                // ⚠️ 拖动过程中**绝不下发 seekTo**。
                // 用户每挪一格进度条就触发一次 onProgressChanged，一次拖动可达几十次；
                // 每次 seekTo 都会 flush 解码器并重新从关键帧解码（HEVC 在老设备上单次
                // seek 就要几百毫秒）。几十次连发的结果就是：解码器/音频输出被反复重置，
                // 轻则卡死无响应，重则音频通道初始化失败 → 一直没声音。
                // 正确做法：拖动期间只更新本地预览文案，松手（onStopTrackingTouch）才 seek 一次。
                if (fromUser) {
                    val dur = player?.duration ?: 0L
                    if (dur > 0) {
                        seekBarPreviewMs = p.toLong() * dur / 1000
                        positionText.text = formatMs(seekBarPreviewMs)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                tracking = true
                seekBarPreviewMs = 0L
                // 拖动进度条属于用户明确诉求的「要显示边栏」场景：主动亮出进度条
                showControls()
                // 拖动期间先暂停，避免「一边播一边被反复 seek」叠加解码压力；
                // 松手后再按播放状态恢复。
                wasPlayingBeforeSeek = player?.isPlaying == true
                player?.pause()
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                tracking = false
                val exo = player ?: return
                // 切集准备期：进度条上还是上一集的时间轴，对它 seek 没意义，
                // 而且 flush 解码器可能打断新集的 prepare。
                if (switchingEpisode) return
                val dur = exo.duration
                if (dur > 0) {
                    val target = seekBarPreviewMs.takeIf { it > 0 }
                            ?: (sb.progress.toLong() * dur / 1000)
                    // 用户手动拖动：作废待执行的续播 seek（READY 回调会覆盖用户刚拖的位置）
                    pendingResumeMs = 0L
                    val gen = playGen
                    skipSeekGroup(gen)
                    exo.seekTo(target)
                    // 弹幕重基准 + 落盘历史（用准确的 target，seekTo 后 currentPosition 可能还是旧值）
                    danmakuView.setPositionMs(target)
                    recordHistory(force = true)
                    if (wasPlayingBeforeSeek) exo.play()
                } else {
                    // duration 读不到（prepare 未完成）：不 seek，但要把暂停态还回去，
                    // 否则会出现「拖一下就没声/不动了」。
                    if (wasPlayingBeforeSeek) exo.play()
                }
                // 松手后：原本在播就继续播放并收起边栏，原本暂停就保持边栏可见。
                if (wasPlayingBeforeSeek) hideControlsNow() else showControls()
            }
        })
    }

    private fun playCurrent() {
        if (vidList.isEmpty()) {
            // 没有可播内容：提示而不是 finish()，避免用户看到"莫名其妙退回选集"
            releaseSwitchGuard()
            showError(getString(R.string.empty))
            return
        }
        if (currentPos < 0 || currentPos >= vidList.size) {
            AppLog.d("PlayerActivity", "playCurrent 越界 pos=$currentPos size=${vidList.size}，忽略")
            releaseSwitchGuard()
            return
        }
        val vid = vidList[currentPos]

        // 取消上一次未完成的取流/解密请求，避免旧响应晚到覆盖新集
        playJob?.cancel()
        playGen++
        val gen = playGen

        // 起播前清掉上一集的失败标记
        currentEpisodeFailed = false
        retryLimited429 = false

        // 切集时取消在途的预加载任务：它下载的是「按旧的下一集算出的目标」，
        // 继续跑只会白抢带宽（且可能与本集写出同一个文件）。
        // 注意：不删已下载的缓存文件，留给 findCachedClearFile 命中。
        prefetchJob?.cancel()
        prefetchJob = null

        // 切集/起播时清空弹幕缓存与画幅元数据（下一集可能横竖屏不同）
        danmakuFetchedWindows.clear()
        danmakuAttempts.clear()
        danmakuRetryAt.clear()
        lastDanmakuWindow = -1L
        danmakuView.reset()
        tkhdWidth = 0; tkhdHeight = 0; tkhdRotation = 0
        videoWidth = 0; videoHeight = 0
        resumedThisEpisode = false
        pendingResumeMs = 0L
        // 本集实际档位未知（缓存命中时拿不到 videoModel 的 height，菜单文案取「未知」）
        currentVariantHeight = 0
        currentVariantCodec = ""
        availableHeights = emptySet()

        updateEpisodeInfo()
        // 无感切集：不立刻弹转圈，延迟 400ms 仍没出画面再显示（见 scheduleLoading）
        scheduleLoading()
        errorPanel.visibility = View.GONE

        // 挂兜底：无论 STATE_READY / onPlayerError 谁先到（或都没到），
        // 到点都强制解除切集抑制，保证上下键永不被锁死。
        handler.removeCallbacks(switchGuardRunnable)
        handler.postDelayed(switchGuardRunnable, SWITCH_GUARD_MS)

        playJob = scope.launch {
            try {
                // 预加载命中：本地已有明文 mp4，直接起播，跳过取流+下载+解密（消除切集卡顿）
                val cached = withContext(Dispatchers.IO) { findCachedClearFile(vid) }
                if (cached != null) {
                    if (gen != playGen) return@launch
                    AppLog.d("PlayerActivity", "命中预加载缓存 vid=$vid")
                    withContext(Dispatchers.IO) { parseTkhd(cached) }
                    if (gen != playGen) return@launch
                    startPlayback(cached)
                    return@launch
                }
                val video = withContext(Dispatchers.IO) {
                    App.netClient.videoModel(vid, maxVariantHeight())
                }
                if (gen != playGen) return@launch
                // 记录服务端实际给出的档位（服务端可能没有所请求档，会回退到最接近的）
                currentVariantHeight = video.height
                currentVariantCodec = video.codec
                // 记录本集实际可用的清晰度集合（变体已在 NetClient 侧按设备能力过滤，
                // 留下的都是本机可播的档位），清晰度菜单只显示这些档（D-02）
                availableHeights = video.variants.map { it.height }.filter { it > 0 }.toSet()
                // 【检验环节2】本集最终可用档位（已过滤 bytevc2/无 HEVC 变体）。
                // 清晰度菜单只会出现这些档；若这里为空说明服务端没给可播资源。
                AppLog.d("PlayerActivity", "【检验环节2】取流 vid=$vid 上限=" + maxVariantHeight()
                        + "P 可用档=" + availableHeights.sortedDescending().joinToString("/") + "P"
                        + " 选中=" + video.height + "P codec=" + video.codec)
                val clearFile = withContext(Dispatchers.IO) { prepareClearFile(video) }
                if (gen != playGen) return@launch
                startPlayback(clearFile)
            } catch (e: Throwable) {
                // ⚠️ 必须 catch Throwable 而非 Exception：1.8GB 内存的老设备上
                // remux 瞬时占用可达 ~80MB，触发 OutOfMemoryError（属 Error 不属
                // Exception）。此前漏接导致整个协程崩掉、播放页异常退出。
                if (gen != playGen) return@launch
                AppLog.e("PlayerActivity", "起播失败 vid=$vid", e)
                hideLoading()
                currentEpisodeFailed = true
                // 起播失败也必须解除切集抑制，否则之后所有 STATE_ENDED 都被忽略，
                // 用户按上下键切集将永远没有反应。
                releaseSwitchGuard()
                // 媒体 CDN 限流（HTTP 429）：暂停预加载，避免后台继续抢同一限流窗口；
                // 并给出「重试」按钮——旧实现直接硬报「视频下载或解密失败」，
                // 用户只能杀进程等限流窗口过去（MiTV-MFTR0 1.1.3 投诉）。
                var limited = false
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause.message?.contains("HTTP 429") == true) { limited = true; break }
                    cause = cause.cause
                }
                if (limited) {
                    mediaCooldownUntil = System.currentTimeMillis() + PREFETCH_COOLDOWN_MS
                    mediaCooldownActive = true
                    retryLimited429 = true
                }
                // BUG-114：身份被拒（自动换身份+网页兜底都失败）时给出「重试」按钮，
                // 实际是换新设备身份后重播——用户不需要摸到日志页去按。
                showError(
                        if (limited) "服务器繁忙，请稍后重试"
                        else (e.message ?: getString(R.string.load_failed)),
                        canRetry = limited || e.message == NetClient.DEVICE_IDENTITY_FAILED_MSG)
            }
        }
    }

    /**
     * 当前清晰度上限（像素高），0 表示不限制。
     *
     * 「自动」沿用设备能力判定（低端设备压 720，见 [deviceCapHeight]）；
     * 其余档位取用户显式选择的上限。传给 [NetClient.videoModel] 后由服务端
     * 变体列表里挑「不超过上限的最优档」。
     */
    private fun maxVariantHeight(): Int {
        val mode = QUALITY_MODES[qualityIndex]
        return if (mode.capHeight < 0) deviceCapHeight else mode.capHeight
    }

    /** 设备能力上限：运行期恒定，只判定一次（isLowEndDevice 会读 /proc/meminfo）。 */
    private val deviceCapHeight: Int by lazy {
        try {
            if (DeviceInfo.isLowEndDevice()) 720 else 0
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * 明文缓存文件的档位标记。**文件名必须带档位**，否则切换清晰度后会误命中
     * 其它档位遗留的旧缓存——用户以为切了 1080P 实际仍在播 720P。
     */
    private fun cacheTag(): String = when (QUALITY_MODES[qualityIndex]) {
        QualityMode.AUTO -> if (deviceCapHeight > 0) "auto720" else "auto"
        QualityMode.P480 -> "480"
        QualityMode.P720 -> "720"
        QualityMode.P1080 -> "1080"
        QualityMode.ORIGIN -> "full"
    }

    /** 明文缓存文件（路径含档位标记，切档后自然不复用旧档文件）。 */
    private fun clearFile(vid: String): File =
        File(cacheDir, "clear_" + vid + "_" + cacheTag() + ".mp4")

    /** 查找该 vid 已预加载的明文缓存文件（存在且非空才算命中）。 */
    private fun findCachedClearFile(vid: String): File? {
        return try {
            val f = clearFile(vid)
            if (f.exists() && f.length() > 1024) f else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 删除该 vid 的**所有档位**明文缓存（BUG-103）。
     * 解码失败自愈时必须全删：缓存里就是解不动的码流，留任何一个档位的旧文件，
     * 下次重试命中后又会在同一码流上失败。
     */
    private fun deleteAllCachedClearFiles(vid: String) {
        try {
            val prefix = "clear_" + vid + "_"
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.name.endsWith(".mp4")) {
                    if (!f.delete()) AppLog.d("PlayerActivity", "缓存删除失败: " + f.name)
                }
            }
        } catch (e: Throwable) {
            AppLog.d("PlayerActivity", "删除解码失败缓存失败: " + (e.message ?: ""))
        }
    }

    /** 下载 → 解密去封装（仅加密变体）→ 写本地明文 mp4，返回文件。运行在 IO 线程。 */
    private fun prepareClearFile(video: NetClient.VideoModel): File {
        val downloader = CencMp4Downloader(HttpClientProvider.get())
        val variants = video.variants
        // R-01：起播总时间预算。变体逐个撞限流时最坏要转圈 5 分钟以上
        //（每变体退避约 22s × 3 个变体），用户只能干看着圈。预算内试完，
        // 超时即放弃剩余变体早报错——用户点重试比无望等待更有用。
        val budgetStart = System.currentTimeMillis()
        fun remainingBudget(): Long =
            STARTUP_MEDIA_BUDGET_MS - (System.currentTimeMillis() - budgetStart)
        // 保留最后一次底层异常（429/超时/重置），起播失败时用于判定限流并透传给用户
        var lastError: Exception? = null

        if (variants.isNotEmpty()) {
            for (v in variants) {
                if (remainingBudget() <= 0L) {
                    AppLog.d("PlayerActivity", "起播预算 " +
                            (STARTUP_MEDIA_BUDGET_MS / 1000) + "s 耗尽，放弃剩余变体 codec=" + v.codec)
                    break
                }
                // ⚠️ 明文变体（encrypted=false）必须直下，绝不能跳过。
                // 旧实现 `if (!v.encrypted) continue` 会把明文变体一律丢弃，
                // 只留加密变体去走 CENC 解密；而服务端并非所有片源都加密——
                // 线上 MiTV-MFTR0 日志里三个变体全是 `encrypted=false`，
                // 于是循环空转、必然抛「视频下载或解密失败」，用户完全看不了这一集。
                // 正确做法：明文直下写盘（无需密钥），加密才走 remux 解密。
                try {
                    val t0 = System.currentTimeMillis()
                    val enc = downloader.downloadWithRetry(v.urls, remainingBudget())
                    if (enc.isEmpty()) {
                        AppLog.d("PlayerActivity", "变体下载为空，跳过 codec=" + v.codec
                                + " height=" + v.height)
                        continue
                    }
                    val clear = if (v.encrypted) {
                        val key = v.cencKey
                        if (key == null) {
                            AppLog.d("PlayerActivity", "加密变体无密钥，跳过 codec=" + v.codec)
                            continue
                        }
                        CencMp4Remuxer.remux(enc, key)
                    } else {
                        enc
                    }
                    val file = clearFile(video.videoId)
                    file.writeBytes(clear)
                    parseTkhd(file)
                    AppLog.d("PlayerActivity", "prepareClearFile codec=" + v.codec
                            + " height=" + v.height + " encrypted=" + v.encrypted
                            + " enc=" + enc.size
                            + " clear=" + clear.size + " 耗时(ms)=" + (System.currentTimeMillis() - t0)
                            + " tkhd=" + tkhdWidth + "x" + tkhdHeight + " rot=" + tkhdRotation)
                    return file
                } catch (e: Exception) {
                    // 保留「可重试」异常（429/5xx/瞬断）优先：变体1 撞 429、变体2
                    // 报 404 时若被 404 覆盖，上层看不出限流，既不给冷却也不给重试
                    // 按钮（QA BUG-003）。
                    if (lastError == null || isRetryableMediaError(e)) lastError = e
                    AppLog.e("PlayerActivity", "变体下载/解密失败 codec=" + v.codec
                            + " encrypted=" + v.encrypted, e)
                }
            }
        } else {
            // 向后兼容：variants 为空时退回 urls + cencKey
            try {
                val enc = downloader.downloadWithRetry(video.urls, remainingBudget())
                val clear = if (video.encrypted && video.cencKey != null) {
                    CencMp4Remuxer.remux(enc, video.cencKey)
                } else {
                    enc
                }
                val file = clearFile(video.videoId)
                file.writeBytes(clear)
                parseTkhd(file)
                return file
            } catch (e: Exception) {
                if (lastError == null || isRetryableMediaError(e)) lastError = e
                AppLog.e("PlayerActivity", "下载/解密失败", e)
            }
        }
        // 带上 cause：起播失败处理靠 cause 链里的「HTTP 429」判定媒体限流
        throw Exception("视频下载或解密失败", lastError)
    }

    /** 媒体下载异常是否属瞬态可重试（429/5xx/网络瞬断）；403/404 等永久错误不算。 */
    private fun isRetryableMediaError(e: Exception): Boolean {
        val m = e.message ?: return true
        if (!m.startsWith("HG媒体下载 HTTP ")) return true
        val code = m.removePrefix("HG媒体下载 HTTP ")
        return code == "429" || code.startsWith("5")
    }

    /**
     * 从解密后的 mp4 解析 tkhd 显示宽高与旋转角（用于竖屏画幅适配）。
     * 只读文件头部（moov 通常在前 256KB 内），不整文件载入内存（单集约 10MB）。
     */
    private fun parseTkhd(clear: File) {
        try {
            val head = ByteArray(MOOV_PROBE_BYTES)
            // read() 单次可能不满读（moov 需 ~104KB），循环读满
            var read = 0
            RandomAccessFile(clear, "r").use { r ->
                while (read < head.size) {
                    val n = r.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
            }
            if (read <= 0) return
            val top = CencMp4Parser.parse(head, 0, read)
            for (trak in CencMp4Parser.findRecursive(top, "trak")) {
                val g = CencMp4Parser.displayGeometry(head, trak)
                // 只取视频轨（宽高非 0 的那条；音频轨宽高为 0）
                if (g.width > 0 && g.height > 0) {
                    tkhdWidth = g.width
                    tkhdHeight = g.height
                    tkhdRotation = g.rotation
                    // BUG-103：缓存命中路径拿不到 videoModel 的档位元数据（直接
                    // startPlayback），HEVC 软解守卫会因 codec=""、height=0 空转，
                    // 1.8GB 电视的缓存一旦是 HEVC 1080p 就会白切软解 OOM。
                    // 这里从明文 mp4 的 stsd/tkhd 回填；只在「未知」时回填，
                    // 绝不覆盖 videoModel 给出的权威档位。
                    if (currentVariantCodec.isEmpty()) {
                        currentVariantCodec = codecAliasOf(CencMp4Parser.videoSampleCodec(head, trak))
                    }
                    if (currentVariantHeight <= 0) {
                        currentVariantHeight = maxOf(g.width, g.height)
                    }
                    return
                }
            }
        } catch (e: Exception) {
            AppLog.d("PlayerActivity", "tkhd 解析失败: " + (e.message ?: ""))
        }
    }

    /** stsd fourcc → 业务侧 codec 别名（与 NetClient 的 codec_type 对齐，供 HEVC 守卫判断）。 */
    private fun codecAliasOf(fourcc: String): String = when (fourcc) {
        "hvc1", "hev1", "encv", "bvc1" -> "bytevc1"
        "avc1", "avc3" -> "h264"
        else -> fourcc
    }

    private fun startPlayback(file: File) {
        val p = player
        if (p == null) {
            // Activity 已销毁：解除抑制并按失败处理，避免标记泄漏
            releaseSwitchGuard()
            return
        }
        // 播放器上挂的内容已是本集（此后 recordHistory 才会写本集进度）
        loadedPos = currentPos
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        applySpeed()

        // 续播：只在「本集尚未续播过」时计算一次待续播位置，真正的 seek 放到
        // STATE_READY（duration 已知）里做——见 applyPendingResume()。
        if (!resumedThisEpisode) {
            resumedThisEpisode = true
            pendingResumeMs = try {
                App.watchHistory.positionOf(seriesId, currentEpisode())
            } catch (_: Exception) {
                0L
            }
        }

        // 关键：起播过程中若已切到后台（用户按了主页），不能 auto-play，否则会
        // 在后台出声——这正是用户反馈的「返回后还有声音」。等回前台再续播。
        if (isVisibleNow()) {
            p.playWhenReady = true
            shouldBePlaying = true
        } else {
            p.playWhenReady = false
            shouldBePlaying = false
        }

        // loading 兜底：正常由首帧 / STATE_READY 隐藏；这里再挂一个上限，
        // 防止个别 ROM 不回调 onRenderedFirstFrame 导致转圈不停。
        // 注意不在这里直接隐藏：解码器初始化完不等于出画面，早收会先看到黑屏。
        handler.removeCallbacks(hideLoadingRunnable)
        handler.postDelayed(hideLoadingRunnable, FIRST_FRAME_HIDE_MS)
        recordHistory(force = true)
        // 注意：switchingEpisode 由 playerListener 在收到 STATE_READY 时复位，
        // 覆盖到解码器初始化完成那一刻（比在这里复位更安全）。
    }

    /**
     * STATE_READY 回调里执行待续播 seek。
     *
     * 此时 duration 已可信，可安全判断上界：上次位置若已接近片尾（说明这集其实
     * 已经看完，只是历史里留了个片尾位置），则从头播——否则 seek 过去会立刻
     * STATE_ENDED 并被误判为「播完」而自动跳下一集。
     */
    private fun applyPendingResume() {
        val p = player ?: return
        val saved = pendingResumeMs
        pendingResumeMs = 0L
        if (saved <= RESUME_MIN_MS) return
        val dur = p.duration
        // ⚠️ 片尾判定要用「续播余量」而非「播完容差」：END_TOLERANCE_MS 只有 1.5s，
        // 意味着上次停在「距片尾 4 秒」处仍会续播——可用户刚进去还没来得及看，
        // 4 秒后 STATE_ENDED 就触发自动跳下一集，观感仍是"点进去就跳集"。
        // 短剧单集普遍只有 1~2 分钟，用 15 秒（或时长的 10%，取小）作余量：
        // 剩余不足这么多就认为这集其实已看完，直接从头播。
        val margin = minOf(RESUME_TAIL_MARGIN_MS, dur / 10).coerceAtLeast(END_TOLERANCE_MS)
        if (dur > 0 && saved >= dur - margin) {
            AppLog.d("PlayerActivity", "上次位置接近片尾，从头播 saved=$saved dur=$dur margin=$margin")
            try { p.seekTo(0L) } catch (_: Exception) {}
            return
        }
        try {
            p.seekTo(saved)
        } catch (_: Exception) {
            return
        }
        AppLog.d("PlayerActivity", "续播命中 ep=${currentEpisode()} pos=${saved}ms")
        toast(getString(R.string.resume_hint, currentEpisode().toString(), formatMs(saved)))
    }

    /** Activity 是否处于可见（已开始）状态；onStop 之前视为可见。 */
    private fun isVisibleNow(): Boolean {
        return try {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (_: Exception) {
            true
        }
    }

    private fun togglePlayPause() {
        // 切集准备期：播放器里挂的还是上一集，此时"继续"会把旧集的声音放出来
        if (switchingEpisode) return
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        recordHistory(force = true)
        // 暂停才显示边栏；恢复播放交给 onIsPlayingChanged 收起。
        if (!p.isPlaying) showControls()
    }

    /** 循环切换画幅模式并持久化。 */
    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % ASPECT_MODES.size
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ASPECT, aspectMode).apply()
        updateAspectLabel()
        applyAspectRatio()
        toast(getString(R.string.menu_fill) + "：" + getString(ASPECT_MODES[aspectMode].labelRes))
    }

    /** 更新画幅菜单文案为「画面填充：模式名」。 */
    private fun updateAspectLabel() {
        menuFill.text = getString(R.string.menu_fill) + "：" + getString(ASPECT_MODES[aspectMode].labelRes)
    }

    /**
     * 切换横/竖屏（手机专用）：大部分短剧是竖屏，但横版画幅的片子需要横屏看。
     * manifest 已声明 orientation|screenSize 不重建 Activity，切方向不会打断播放。
     * 选择落盘，下次进播放页沿用；退出后仍是用户上次选的形态。
     */
    private fun toggleRotate() {
        landscapeOn = !landscapeOn
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_LANDSCAPE, landscapeOn).apply()
        requestedOrientation = if (landscapeOn)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        updateRotateLabel()
        toast(getString(R.string.menu_rotate) + "：" +
                getString(if (landscapeOn) R.string.rotate_landscape else R.string.rotate_portrait))
    }

    /** 更新翻转菜单文案为「翻转：当前形态」。 */
    private fun updateRotateLabel() {
        menuRotate.text = getString(R.string.menu_rotate) + "：" +
                getString(if (landscapeOn) R.string.rotate_landscape else R.string.rotate_portrait)
    }

    /**
     * 循环切换清晰度档位：自动 → 480P → 720P → 1080P → 原画 → 自动。
     *
     * D-02：只在「本集实际可用」的档位间循环（见 [availableQualityIndices]），
     * 服务端没有的档位不再出现在循环里——旧实现五个档全转一遍，选到不存在
     * 的档只会被服务端回落，用户以为切上了实际没变。
     *
     * 切档后**当前集必须重新取流**：清晰度是在取流阶段（videoModel 的变体过滤）
     * 决定的，仅改内存字段不会影响已下载的明文缓存，所以先清掉本集旧档缓存再起播。
     */
    private fun cycleQuality() {
        val avail = availableQualityIndices()
        val cur = avail.indexOf(qualityIndex)
        // 当前档不在可用列表（比如上一集持久化的档位本集没有）时回退到「自动」
        qualityIndex = if (cur >= 0) avail[(cur + 1) % avail.size] else avail[0]
        // 【检验环节2】切档时记录可用档位与新选中档（排查「切完没反应/缺档」）
        AppLog.d("PlayerActivity", "【检验环节2】切档 可用下标=" + avail
                + " → 新档下标=" + qualityIndex
                + " 上限=" + QUALITY_MODES[qualityIndex].capHeight + "P")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_QUALITY, qualityIndex).apply()
        updateQualityLabel()
        val label = getString(QUALITY_MODES[qualityIndex].labelRes)
        toast(getString(R.string.quality_switched, label))
        pendingQualityHint = true
        // D-04：只清当前集的旧档缓存。旧实现 cleanupClearFiles(keep=null) 会把
        // 预加载窗口里其他集已下载好的缓存一并删光——切清晰度只影响当前集，
        // 其他集的缓存按「新档位下载新文件」共存即可（文件名带档位，互不误命中），
        // 切回去时旧档缓存仍然命中，不用重新下载。
        clearVidCacheFiles(vidList.getOrNull(currentPos))
        replayCurrentEpisode()
    }

    /**
     * 当前剧集可用的清晰度档位下标（D-02：只显示可用清晰度）。
     *
     * 「自动」恒可用；480/720/1080 只有当本集变体里存在不超过该上限的档
     * （选了才真的有对应档可下）才出现；「原画」只要本集有可用档就出现。
     * 变体未知（命中本地缓存、还没取流）时不设限，避免菜单突然缺档。
     */
    private fun availableQualityIndices(): List<Int> {
        if (availableHeights.isEmpty()) return QUALITY_MODES.indices.toList()
        val out = ArrayList<Int>()
        for (i in QUALITY_MODES.indices) {
            when (val cap = QUALITY_MODES[i].capHeight) {
                -1, 0 -> out.add(i)  // 自动（按设备能力）/ 原画（不限制）恒可用
                else -> if (availableHeights.any { it in 1..cap }) out.add(i)
            }
        }
        // 兜底：极端情况（全集只有超限的高档）至少保底「自动」可转
        if (out.isEmpty()) out.add(QUALITY_AUTO)
        return out
    }

    /**
     * 本集实际取到的档位是否**高于**所选上限（HGTV 的「兼容资源」回落语义）。
     *
     * HGTV stream() 的选档规则是「取 ≤ 上限的最高档；若全部超限，就退到最低的一档」——
     * 退回来的那档仍然超过上限，所以 HGTV 在文案上标注「（兼容资源）」。
     * 例：设备无 1080P 硬解能力（上限 720）但本集最低只有 1080P 可播档 → 播 1080P
     * 并提示兼容资源，让用户知道这不是所选档位。
     */
    private fun isCompatFallback(): Boolean {
        if (currentVariantHeight <= 0) return false
        val cap = QUALITY_MODES[qualityIndex].capHeight
        val limit = if (cap < 0) deviceCapHeight else cap
        return limit in 1 until currentVariantHeight
    }

    /** 更新清晰度菜单文案为「清晰度：档位名(+实际档)」。 */
    private fun updateQualityLabel() {
        val label = getString(QUALITY_MODES[qualityIndex].labelRes)
        val actual = if (currentVariantHeight > 0) "（当前 " + currentVariantHeight + "P）" else ""
        menuQuality.text = getString(R.string.menu_quality) + "：" + label + actual
    }

    /**
     * 切档后重播本集：保留播放位置，重新走取流+下载+解密。
     * 与 [rebuildPlayer] 的区别是不重建播放器（解码方式没变）。
     *
     * 位置接续走「先落盘历史、playCurrent 起播时按历史续播」这条现成链路——
     * playCurrent 会重置 resumedThisEpisode/pendingResumeMs，在这里提前赋值会被覆盖。
     */
    private fun replayCurrentEpisode() {
        recordHistory(force = true)
        releasePlayer()
        playCurrent()
    }

    /** 切档后首个 STATE_READY：提示一次实际生效档位（服务端可能无该档而回落）。 */
    private fun notifyQualityIfNeeded() {
        if (!pendingQualityHint) return
        pendingQualityHint = false
        val label = getString(QUALITY_MODES[qualityIndex].labelRes)
        // HGTV 同款「兼容资源」标注：服务端给的档比所选上限还高（全超限回落）时提示，
        // 避免用户以为切上了 1080P 实际播的是别的档。
        val compat = if (isCompatFallback()) "（兼容资源）" else ""
        val actual = if (currentVariantHeight > 0) currentVariantHeight.toString() + "P" else "未知"
        toast(getString(R.string.menu_quality) + " " + label + compat + " → 实际 " + actual)
        updateQualityLabel()
    }

    /**
     * 创建播放器。软解时用自定义 RenderersFactory 只保留 Audio/Video 软解码器；
     * 硬解时用系统默认（MediaCodec 优先，软解兜底）。
     */
    private fun buildPlayer(): ExoPlayer {
        // 【检验环节3】解码器工厂选择。真机排查「起播即黑/解码失败」时先看这条：
        // 硬解=MediaCodec 优先且解码器回退已开；软解=SoftwareRenderersFactory。
        AppLog.d("PlayerActivity", "【检验环节3】创建播放器 模式="
                + if (decoderSoft) "软解" else "硬解(解码器回退已开)")
        val builder = ExoPlayer.Builder(this)
        if (decoderSoft) {
            builder.setRenderersFactory(SoftwareRenderersFactory(this))
        } else {
            // HGTV MainActivityPlayer 同款：硬解时开启解码器回退。
            // 有的 ROM 谎报解码能力——MediaCodecList 里有 video/hevc 解码器，
            // 但初始化后解这个码流直接失败。setEnableDecoderFallback(true) 让
            // ExoPlayer 在解码器初始化/解码失败时自动换下一个解码器（含软解），
            // 而不是立刻黑屏报错，多一层自救（与 onPlayerError 的两级自愈互补）。
            builder.setRenderersFactory(
                DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            )
        }
        return builder.build()
    }

    /**
     * STATE_READY 后视频轨是否真的被选中。
     *
     * 参考 HGTV MainActivityPlayer：`if (!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))`
     * ——MP4 容器里只有音频轨时 ExoPlayer 照样进 READY，靠这个检查才能拦住
     * 「有声音没画面」的假播放。判定异常时按正常处理，不误伤正常播放。
     */
    private fun hasVideoTrack(): Boolean {
        return try {
            player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) ?: false
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * 本集只解出音频轨（无视频轨可解码）：停播并给出可读错误。
     *
     * HGTV stream() 同款语义——它的做法是抛「资源没有可解码的视频轨道」错误，
     * 而不是让用户对着黑屏听声音。不自动跳下一集：audio-only 往往是该集
     * 资源本身的问题，自动跳会连环踩坑；上下键切集仍然可用。
     */
    private fun onNoVideoTrack() {
        if (currentEpisodeFailed) return
        currentEpisodeFailed = true
        AppLog.d("PlayerActivity", "【检验环节4】STATE_READY 无视频轨（audio-only），快速失败")
        try { player?.stop() } catch (_: Exception) {}
        showError(getString(R.string.play_error_no_video_track))
    }

    /**
     * 【检验环节4】STATE_READY 成功路径：记录视频轨实际选中的解码器与输入格式。
     *
     * 真机排查「画面正常但想知道到底用的硬解还是软解、解的什么编码」看这条；
     * 若视频轨存在但 renderer 未启任何 decoder，说明回退后仍无可解码器。
     */
    private fun logReadyVideoTrack() {
        try {
            val tracks = player?.currentTracks ?: return
            val sb = StringBuilder("【检验环节4】READY 视频轨: ")
            var selected = false
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val sel = group.isTrackSelected(i)
                    if (sel) selected = true
                    sb.append("[")
                        .append(if (sel) "选中" else "备选")
                        .append(" ")
                        .append(f.sampleMimeType ?: "?")
                        .append(" ")
                        .append(f.width).append("x").append(f.height)
                        .append(if (f.codecs.isNullOrEmpty()) "" else " " + f.codecs)
                        .append("]")
                }
            }
            AppLog.d("PlayerActivity", if (selected) sb.toString() else sb.toString() + "（无选中轨！）")
        } catch (e: Throwable) {
            AppLog.d("PlayerActivity", "【检验环节4】视频轨日志失败: " + (e.message ?: ""))
        }
    }

    /**
     * 切换解码方式并立刻生效：释放旧播放器、按新方式重建、从当前位置续播。
     * 因为解码器是构建期决定的（RenderersFactory），无法热切换，必须重建播放器。
     */
    private fun cycleDecoder() {
        decoderSoft = !decoderSoft
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DECODER_SOFT, decoderSoft).apply()
        updateDecoderLabel()
        val label = getString(if (decoderSoft) R.string.menu_decoder_soft else R.string.menu_decoder_hard)
        toast(getString(R.string.menu_decoder_switched, label))
        rebuildPlayer()
    }

    /** 用当前解码方式重建播放器，保持本集播放位置与播放状态。 */
    private fun rebuildPlayer() {
        val old = player ?: return
        val resumeMs = try { old.currentPosition } catch (_: Exception) { 0L }
        val wasPlaying = old.isPlaying
        try { old.release() } catch (_: Exception) {}
        player = buildPlayer()
        player!!.setVideoSurfaceView(surfaceView)
        player!!.setAudioAttributes(AudioAttributes.DEFAULT, /*handleAudioFocus=*/true)
        try { player!!.setWakeMode(C.WAKE_MODE_NETWORK) } catch (_: Exception) {}
        player!!.addListener(playerListener)
        pendingResumeMs = resumeMs
        resumedThisEpisode = true
        AppLog.d("PlayerActivity", "重建播放器 decoderSoft=$decoderSoft resume=${resumeMs}ms")
        playCurrent()
    }

    /** 更新解码菜单文案为「解码：硬解 / 软解」。 */
    private fun updateDecoderLabel() {
        menuDecoder.text = getString(R.string.menu_decoder) + "：" +
                getString(if (decoderSoft) R.string.menu_decoder_soft else R.string.menu_decoder_hard)
    }

    /** 循环切换倍速档位并持久化。 */
    private fun cycleSpeed() {        speedIndex = (speedIndex + 1) % SPEEDS.size
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_SPEED, speedIndex).apply()
        updateSpeedLabel()
        applySpeed()
        toast(getString(R.string.menu_speed) + "：" + SPEEDS[speedIndex].toString() + "x")
    }

    /** 将当前倍速应用到播放器与弹幕时钟。 */
    private fun applySpeed() {
        player?.setPlaybackParameters(PlaybackParameters(SPEEDS[speedIndex]))
        danmakuView.setRate(SPEEDS[speedIndex])
    }

    private fun updateSpeedLabel() {
        menuSpeed.text = getString(R.string.menu_speed) + "：" + SPEEDS[speedIndex].toString() + "x"
    }

    /** 切换弹幕开关并持久化。 */
    private fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DANMAKU, danmakuEnabled).apply()
        updateDanmakuLabel()
        applyDanmakuVisibility()
        // 重新打开弹幕时，当前窗口可能已被标记为"已拉取"（当时开关是关的，拉取被
        // 跳过），这里清掉标记让它重新拉一次，否则要等下一个 30s 窗口才出弹幕。
        if (danmakuEnabled) {
            danmakuFetchedWindows.clear()
            danmakuAttempts.clear()
            danmakuRetryAt.clear()
            lastDanmakuWindow = -1L
        }
        toast(if (danmakuEnabled) getString(R.string.menu_danmaku_on) else getString(R.string.menu_danmaku_off))
    }

    private fun updateDanmakuLabel() {
        menuDanmaku.text = if (danmakuEnabled) getString(R.string.menu_danmaku_on) else getString(R.string.menu_danmaku_off)
    }

    private fun applyDanmakuVisibility() {
        danmakuView.visibility = if (danmakuEnabled) View.VISIBLE else View.GONE
    }

    /** 每个 500ms 轮询里更新弹幕时钟，并在跨入新的 30s 窗口时触发拉取。 */
    private fun updateDanmaku(p: ExoPlayer, pos: Long, dur: Long) {
        if (!danmakuEnabled) return
        danmakuView.setPositionMs(pos)
        danmakuView.setPlaying(p.isPlaying)
        danmakuView.setRate(SPEEDS[speedIndex])
        if (dur <= 0) return
        val window = pos / DANMAKU_WINDOW_MS * DANMAKU_WINDOW_MS
        if (window == lastDanmakuWindow) return
        // D-08：先查失败退避，**未到重试时间不能写 lastDanmakuWindow**——
        // 旧实现先写再拉，一旦拉取失败，同一窗口在播放跨出这 30 秒之前
        // 都被 `window == lastDanmakuWindow` 挡住，永远没有第二次机会。
        val attempts = danmakuAttempts[window] ?: 0
        if (attempts >= DANMAKU_MAX_ATTEMPTS) return
        val retryAt = danmakuRetryAt[window] ?: 0L
        if (retryAt > System.currentTimeMillis()) return
        lastDanmakuWindow = window
        if (danmakuFetchedWindows.contains(window)) return
        fetchDanmaku(window, dur)
    }

    /**
     * 预加载当前集之后的若干集：提前请求 videoModel 并下载解密到本地缓存文件。
     * 切集（含选集弹窗跳转）时若缓存命中则跳过网络直接起播，实现丝滑切集。
     *
     * 设计要点：
     *  - **串行**下载（一次一集）：并发会抢带宽拖慢当前集，且 remux 瞬时内存
     *    占用叠加，低内存盒子（823MB）容易 OOM。
     *  - **以文件存在为准**判断是否需要预取，自校正、无内存状态漂移。
     *  - 由 500ms 进度回调反复调用，切集后新窗口会自动补上；prefetchRunning
     *    保证同一时刻只有一个预取循环在跑。
     *  - 失败后不立即重试该集（记入 failedPos），避免请求风暴被风控。
     */
    private fun prefetchNext() {
        if (prefetchRunning) return
        // 媒体 CDN 限流冷却中：不发起任何预加载，等窗口过去
        if (System.currentTimeMillis() < mediaCooldownUntil) return
        if (mediaCooldownActive) {
            // 冷却结束：清失败标记，让窗口内的集重新预取
            mediaCooldownActive = false
            failedPrefetch.clear()
        }
        // 找出预取窗口内尚未缓存的集（currentPos+1 .. currentPos+PREFETCH_AHEAD）
        val pending = ArrayList<Int>()
        val end = minOf(currentPos + PREFETCH_AHEAD, vidList.size - 1)
        for (p in (currentPos + 1)..end) {
            val vid = vidList.getOrNull(p) ?: continue
            if (vid in failedPrefetch) continue
            if (findCachedClearFile(vid) != null) continue
            pending.add(p)
        }
        if (pending.isEmpty()) return

        prefetchRunning = true
        val gen = playGen
        prefetchJob = scope.launch {
            try {
                for (pos in pending) {
                    if (gen != playGen) return@launch   // 已切集，本窗口作废
                    val vid = vidList.getOrNull(pos) ?: continue
                    if (findCachedClearFile(vid) != null) continue
                    var ok = false
                    var rateLimited = false
                    try {
                        AppLog.d("PlayerActivity", "预加载 pos=$pos vid=$vid")
                        val video = withContext(Dispatchers.IO) {
                            App.netClient.videoModel(vid, maxVariantHeight())
                        }
                        withContext(Dispatchers.IO) {
                            val downloader = CencMp4Downloader(
                                    HttpClientProvider.get())
                            // ⚠️ 与 prepareClearFile 保持一致：明文变体（encrypted=false）
                            // 必须直下，旧代码 `if (!v.encrypted) continue` 会让全明文片源
                            // 一集都预加载不成功，切到该集时只能现场下载（转圈很久）。
                            if (video.variants.isNotEmpty()) {
                                for (v in video.variants) {
                                    try {
                                        val enc = downloader.downloadWithRetry(v.urls)
                                        if (enc.isEmpty()) continue
                                        val clear = if (v.encrypted) {
                                            val key = v.cencKey
                                            if (key == null) continue
                                            CencMp4Remuxer.remux(enc, key)
                                        } else {
                                            enc
                                        }
                                        clearFile(video.videoId).writeBytes(clear)
                                        ok = true
                                        break
                                    } catch (e: Exception) {
                                        AppLog.e("PlayerActivity", "预加载变体失败", e)
                                        // 429 说明整个媒体 CDN 在限流：换变体（同一批
                                        // 域名）只会继续撞墙，每个变体还要白等一轮退避
                                        //（约 14s）。直接跳出，交给下面统一切换冷却。
                                        if (e.message?.contains("HTTP 429") == true) {
                                            rateLimited = true
                                            break
                                        }
                                    }
                                }
                            }
                            // 兜底仅在「确实拿到 urls」时走，空数组会让下载器内部
                            // urls[0] 越界（MiTV-MFTR0 日志：Index 0 out of bounds for length 0）
                            if (!ok && !rateLimited && video.urls.isNotEmpty()) {
                                try {
                                    val enc = downloader.downloadWithRetry(video.urls)
                                    if (enc.isNotEmpty()) {
                                        val clear = if (video.encrypted && video.cencKey != null) {
                                            CencMp4Remuxer.remux(enc, video.cencKey)
                                        } else {
                                            enc
                                        }
                                        clearFile(video.videoId).writeBytes(clear)
                                        ok = true
                                    }
                                } catch (e: Exception) {
                                    AppLog.e("PlayerActivity", "预加载兜底失败", e)
                                    if (e.message?.contains("HTTP 429") == true) rateLimited = true
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        // 失败不打扰用户：切集时走正常路径（playCurrent 会重新取流）。
                        // 记下 vid 本轮不再重试，避免每 500ms 一次形成请求风暴。
                        if (e.message?.contains("cancel", ignoreCase = true) != true) {
                            failedPrefetch.add(vid)
                        }
                        if (e.message?.contains("HTTP 429") == true) rateLimited = true
                        AppLog.d("PlayerActivity", "预加载失败 pos=$pos: " + (e.message ?: ""))
                    }
                    // 限流熔断必须在 gen 检查之前：切集瞬间撞 429 时（线上最高频
                    // 时序，MiTV 1.1.3 19:21:59.846），冷却若被 gen 提前 return
                    // 跳过，熔断失效，预加载继续逐集撞限流（QA BUG-001）。
                    if (rateLimited) {
                        // 媒体 CDN 限流：熔断本轮预加载，冷却后再继续。
                        // 旧实现失败也立刻推进下一集，连环请求把限流窗口一直续着。
                        mediaCooldownUntil = System.currentTimeMillis() + PREFETCH_COOLDOWN_MS
                        mediaCooldownActive = true
                        AppLog.d("PlayerActivity", "预加载限流，冷却 ${PREFETCH_COOLDOWN_MS / 1000}s")
                        return@launch
                    }
                    if (gen != playGen) return@launch
                    if (ok) {
                        AppLog.d("PlayerActivity", "预加载完成 pos=$pos vid=$vid")
                    } else {
                        failedPrefetch.add(vid)
                    }
                }
            } finally {
                prefetchRunning = false
            }
        }
    }

    private fun fetchDanmaku(window: Long, dur: Long) {
        val vid = vidList.getOrNull(currentPos) ?: return
        val gen = playGen
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    App.netClient.danmaku(seriesId, vid, window, dur)
                }
                if (gen != playGen) return@launch
                if (!danmakuEnabled) return@launch
                danmakuFetchedWindows.add(window)
                danmakuAttempts.remove(window)
                danmakuRetryAt.remove(window)
                danmakuView.addItems(items)  // 累积合并，不覆盖其他窗口数据
                AppLog.d("PlayerActivity", "弹幕 window=$window 返回 ${items.size} 条")
            } catch (e: Throwable) {
                // ⚠️ 必须是 Throwable 而不是 Exception：NoClassDefFoundError /
                // NoSuchMethodError 这类 LinkageError 继承自 Error，用 Exception 接不住，
                // 会直接冒到 CoroutineExceptionHandler 让 App 闪退
                // （日志实锤：API 22 设备 java.util.Base64 缺失 → 拉弹幕即闪退）。
                // 弹幕是附属功能，任何失败都不该影响观看。
                // D-08：计次 + 5s 退避重试（清掉 lastDanmakuWindow 让 500ms 轮询
                // 在退避到期后重新进入该窗口），而不是死等下一个 30s 窗口。
                val attempts = (danmakuAttempts[window] ?: 0) + 1
                danmakuAttempts[window] = attempts
                if (attempts < DANMAKU_MAX_ATTEMPTS) {
                    danmakuRetryAt[window] = System.currentTimeMillis() + DANMAKU_RETRY_DELAY_MS
                    lastDanmakuWindow = -1L
                }
                AppLog.d("PlayerActivity", "弹幕拉取失败(第" + attempts + "次) window=$window: "
                        + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 按当前模式 + 视频真实比例设置画面缩放。 */
    private fun applyAspectRatio() {
        // 显示比例：竖屏视频（h > w）在横屏 TV 上保持竖条比例，人不会被拉胖
        val displayW = videoWidth.toFloat()
        val displayH = videoHeight.toFloat()
        when (ASPECT_MODES[aspectMode]) {
            AspectMode.FIT -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (displayW > 0 && displayH > 0) {
                    videoContainer.setAspectRatio(displayW / displayH)
                }
            }
            AspectMode.STRETCH -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectMode.ORIGINAL -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            }
            AspectMode.FILL -> {
                videoContainer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                if (displayW > 0 && displayH > 0) {
                    videoContainer.setAspectRatio(displayW / displayH)
                }
            }
        }
    }

    /* ==================== 切集 ==================== */

    private fun switchEpisode(pos: Int) {
        if (switchingEpisode) {
            // 切集准备中（下载+解密可能数秒）：给个反馈，否则用户以为遥控失灵
            toast("正在切换…")
            return
        }
        if (pos < 0) {
            toast(getString(R.string.first_episode))
            return
        }
        if (pos >= vidList.size) {
            toast(getString(R.string.last_episode))
            return
        }
        switchingEpisode = true
        // 先落盘当前集进度，再切走（此刻 currentPos 仍指向旧集，读到的位置有效）
        recordHistory(force = true)
        // 只暂停不 stop：SurfaceView 保留上一集最后一帧（不黑屏），音频立即停。
        // 旧实现在这里 releasePlayer()（stop + clearMediaItems）会把解码器拆掉，
        // 每切一集都先黑屏、再重新初始化解码器，是「切集有感」的最大来源。
        // 新集起播由 startPlayback 直接换 MediaItem（缓存命中时几乎是秒换）。
        player?.pause()
        currentPos = pos
        resumePosition = 0
        // 切集后新的一集是全新上下文：重置本集的失败自愈标记（每个 vid 各自计一次）
        autoHevcSkipVid = null
        autoSoftFailoverVid = null
        ioRetriedVid = null
        // 清理「新预取窗口之外」的明文临时文件。
        // 关键：不能整目录删——新窗口（当前集起 PREFETCH_AHEAD 集）里若已有
        // 预加载文件，删掉会让缓存永远命中不了（预加载失效、切集又变卡）。
        val keep = HashSet<String>()
        val keepEnd = minOf(pos + PREFETCH_AHEAD, vidList.size - 1)
        for (k in pos..keepEnd) vidList.getOrNull(k)?.let { keep.add(it) }
        cleanupClearFiles(keep = keep)
        playCurrent()
        // 切集不弹边栏（用户诉求：只有暂停/拖进度条才显示）；集号变化由
        // 顶栏 episode_info 承载，会在下次暂停/拖动时看到。
        // 注意：switchingEpisode 不在这里复位——要等新集真正 startPlayback
        // 才解除抑制，否则新集准备期间的 STATE_ENDED 会被误判成"本集播完"再次切集。
    }

    /* ==================== 手机端上下滑切集（抖音式） ==================== */

    /**
     * 画面上下滑动切集：上滑 = 下一集，下滑 = 上一集。
     *
     * 只「观察」不抢事件：DOWN/MOVE 照常下发给子视图（单击暂停、拖进度条都靠它），
     * 只有纵向位移超阈值且明显压过横向时，才在 UP 这一下由本层吞掉并切集——
     * 吞 UP 是为了不让 SurfaceView 再补发一次点击（否则滑一下会顺手把播放暂停）。
     * 菜单/选集弹窗展开、或正在拖进度条时不参与（手势各有归属）。
     * TV 端不启用（遥控器上下键本来就是切集）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (UiScale.isTv(this)) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeCandidate = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeCandidate) {
                    val dy = abs(ev.y - swipeDownY)
                    val dx = abs(ev.x - swipeDownX)
                    swipeCandidate = dy > dp(SWIPE_SLOP_DP) && dy > dx * 1.5f
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeCandidate = false
            MotionEvent.ACTION_UP -> {
                val swiped = swipeCandidate
                swipeCandidate = false
                if (swiped && !tracking &&
                    menuPanel.visibility != View.VISIBLE &&
                    epPickerRoot.visibility != View.VISIBLE &&
                    abs(ev.y - swipeDownY) >= dp(SWIPE_TRIGGER_DP)
                ) {
                    // 上滑（dy<0）→ 下一集；下滑 → 上一集
                    switchEpisode(if (ev.y < swipeDownY) currentPos + 1 else currentPos - 1)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 一集播完。
     *
     * ⚠️ 用户反馈的「点进去观看短剧会闪退回选集界面」根因就在这里：旧实现无条件
     * 认为 STATE_ENDED 就是"本集看完"，末集直接 finish() → 回到选集页。但切集、
     * seek、解码器初始化失败等场景也会瞬时抛出 STATE_ENDED，于是用户刚进播放页
     * 就被弹回选集页，看起来像"闪退"。
     *
     * 修复：① 切集过程中忽略；② 只在确实播到接近片尾时才认为播完；
     *      ③ 末集不再 finish()，而是停在最后一帧并提示，由用户自己退出。
     */
    private fun onEpisodeComplete() {
        if (switchingEpisode) {
            AppLog.d("PlayerActivity", "忽略切集期间的 STATE_ENDED")
            return
        }
        val p = player
        val dur = p?.duration ?: 0L
        val pos = p?.currentPosition ?: 0L
        // 本集已失败（解码/网络）：不能再当"播完"去切下一集，否则会连环失败跳集
        if (currentEpisodeFailed) {
            AppLog.d("PlayerActivity", "本集起播失败，忽略 STATE_ENDED")
            return
        }
        // ⚠️ duration 未知时 ExoPlayer 返回 C.TIME_UNSET（一个很大的负数）或 0。
        // 此时绝不能当成"播完"——解码器初始化失败/无可用轨道也会抛 STATE_ENDED，
        // 误判会导致进播放页立刻自动跳下一集（观感即"闪退到选集"）。一律忽略。
        if (dur <= 0 || pos < dur - END_TOLERANCE_MS) {
            AppLog.d("PlayerActivity", "忽略虚假 STATE_ENDED pos=$pos dur=$dur")
            return
        }
        // 本集确实播完：清掉本集进度（而非存片尾位置）。
        // 若把片尾位置留作续播点，用户下次进这集会被 seek 到片尾 → 立刻又
        // STATE_ENDED → 又自动跳下一集，而下一集的片尾位置同样被留下，形成
        // 「周而复始自动跳集」（用户反馈的恶性 bug）。markEpisodeCompleted 会
        // 清空该集进度并把 lastEp 推进到本集。
        markEpisodeCompleted()
        if (currentPos + 1 < vidList.size) {
            if (autoNext) {
                switchEpisode(currentPos + 1)
            } else {
                // 设置页关掉了自动连播：停在本集最后一帧，由用户自己选集
                toast(getString(R.string.ep_finished_auto_off, currentEpisode().toString()))
                showControls()
            }
        } else {
            // 末集播完：不再自动退出（那是"闪退回选集"的观感），停在原地给提示
            toast(getString(R.string.ep_played_out))
            showControls()
        }
    }

    /** 标记当前集已看完：清空该集进度，避免下次进入又被 seek 到片尾连跳。 */
    private fun markEpisodeCompleted() {
        if (seriesId.isEmpty()) return
        try {
            App.watchHistory.markEpisodeCompleted(seriesId, title, cover, currentEpisode())
        } catch (e: Exception) {
            AppLog.e("PlayerActivity", "标记看完失败", e)
        }
    }

    /** 当前集号：优先取 epList[位置]，列表缺失时退化为「位置 + 1」 */
    private fun currentEpisode(): Int = epList.getOrNull(currentPos) ?: (currentPos + 1)

    /**
     * 写入观看历史（含集内进度）。
     * @param force true 表示关键节点（切集/暂停/退出）必须落盘；false 走节流（10s 一次）
     *
     * WatchHistory 现按「集号→进度」映射存储，写本集不会影响其他集的进度。
     * 仍保留防抹除：位置读不到（prepare 未完成，duration=0 时 currentPosition
     * 恒为 0）时跳过该集的进度写入，只更新时间戳语义由 record() 内部保证。
     */
    private fun recordHistory(force: Boolean = false) {
        if (seriesId.isEmpty()) return
        // 切集准备期：currentPos 已指向新集，但播放器里还挂着上一集的 MediaItem，
        // 此刻读到的位置属于旧集，写进去会污染新集的续播点。等新集 startPlayback
        // 之后再记（那时 loadedPos 才更新为 currentPos）。
        if (loadedPos != currentPos) return
        val now = System.currentTimeMillis()
        if (!force && now - lastHistoryWrite < HISTORY_WRITE_INTERVAL_MS) return
        val p = player
        // 读位置（prepare 未完成时 duration<=0，读作 0 表示"本次拿不到有效位置"）
        var posMs = try {
            if (p != null && p.duration > 0) p.currentPosition else 0L
        } catch (_: Exception) {
            0L
        }
        // ⚠️ 接近片尾的位置一律不写入历史。否则该位置会成为下次的续播点，
        // 用户再进这集就被 seek 到片尾 → 立刻 STATE_ENDED → 自动跳下一集，
        // 而下一集同样留下片尾位置 → 连环跳集（用户反馈的恶性 bug）。
        // 这里"直接 return 不写"而非写 0：record() 对 <=0 是"保留旧值"语义，
        // 写 0 清不掉，反而可能把更早的有效续播点保留下来造成误解。
        if (posMs > 0) {
            val dur = try { p?.duration ?: 0L } catch (_: Exception) { 0L }
            if (dur > 0 && posMs >= dur - END_TOLERANCE_MS) {
                lastHistoryWrite = now
                return
            }
        }
        lastHistoryWrite = now
        try {
            App.watchHistory.record(seriesId, title, cover, currentEpisode(), posMs)
        } catch (e: Exception) {
            AppLog.e("PlayerActivity", "写历史失败", e)
        }
    }

    private fun updateEpisodeInfo() {
        val total = vidList.size
        val text = if (total > 0) "$title · 第 ${currentEpisode()} / $total 集" else title
        episodeInfo.text = text
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.clearMediaItems() } catch (_: Exception) {}
        }
    }

    /**
     * 删除 cacheDir 下 clear_ 前缀的明文临时文件。
     * @param keep 需保留的 vid 集合（当前集与预加载窗口）；为 null 时全清。
     *
     * 文件名形态：`clear_<vid>_<档位>.mp4`（档位 auto/auto720/480/720/1080/full，
     * 旧版还有 `clear_<vid>.mp4` 无档位形态）。这里统一剥离前缀/后缀与档位标记还原 vid。
     * 注意 keep 匹配的是 vid，不带档位——同 vid 的其它档位文件也会被清，
     * 这正是想要的（切档后旧档缓存就该删）。
     */
    private fun cleanupClearFiles(keep: Set<String?>? = null) {
        val tags = arrayOf("_auto720", "_auto", "_480", "_720", "_1080", "_full")
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (!f.name.startsWith("clear_")) return@forEach
                if (keep != null) {
                    var name = f.name.removePrefix("clear_").removeSuffix(".mp4")
                    for (t in tags) name = name.removeSuffix(t)
                    if (keep.contains(name)) return@forEach
                }
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 只删除指定 vid 的明文缓存文件（各档位 + 旧版无档位形态），其余 vid 不动（D-04）。
     *
     * 文件名形态：`clear_<vid>_<档位>.mp4`，旧版还有 `clear_<vid>.mp4`。
     * 这里按第一个 `_` 切出 vid 精确匹配——不能用前缀匹配，否则 vid=123 会把
     * vid=1234 的文件也误删。
     */
    private fun clearVidCacheFiles(vid: String?) {
        if (vid.isNullOrEmpty()) return
        try {
            cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (!n.startsWith("clear_") || !n.endsWith(".mp4")) return@forEach
                val rest = n.removePrefix("clear_").removeSuffix(".mp4")
                val sep = rest.indexOf('_')
                val fileVid = if (sep < 0) rest else rest.substring(0, sep)
                if (fileVid == vid) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun showError(message: String, canRetry: Boolean = false) {
        errorText.text = getString(R.string.play_error, message)
        errorPanel.visibility = View.VISIBLE
        errorRetry.visibility = if (canRetry) View.VISIBLE else View.GONE
        showControls()
    }

    /**
     * 失败提示上的「重试」：实际是换新设备身份（resetIdentity + registerNow）后重播。
     *
     * 用户视角只关心「这个剧能看」，不需要理解「设备身份」——所以按钮与文案都只叫
     * 「重试」，换身份这个实现细节留在代码里。仅出现在「身份被拒」失败（自动换身份
     * 兜底全部失败）时，其他失败（断网/真版权限/解码）不给该入口，避免换身份救不了
     * 的场景诱导用户白白重置。
     *
     * 电视端靠 onKeyDown 拦截确定键触发（按钮不参与焦点导航，不吞上下键切集）；
     * 手机端直接点击。
     */
    private fun retryWithNewIdentity() {
        if (retryIdentityJob?.isActive == true) return
        errorRetry.isEnabled = false
        errorRetry.text = getString(R.string.error_retry_doing)
        // 429 限流与设备身份无关：只重播，不 resetIdentity/registerNow。
        // 换身份救不了限流，还会在窗口内多发一次注册请求（QA BUG-005）。
        if (retryLimited429) {
            AppLog.d("PlayerActivity", "用户点「重试」→ 限流恢复，直接重播（不换设备身份）")
            retryIdentityJob = scope.launch {
                if (isFinishing || isDestroyed) return@launch
                errorRetry.isEnabled = true
                errorRetry.text = getString(R.string.error_retry)
                errorRetry.visibility = View.GONE
                errorPanel.visibility = View.GONE
                playCurrent()
            }
            return
        }
        AppLog.d("PlayerActivity", "用户点「重试」→ 换新设备身份后重播")
        retryIdentityJob = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    DeviceRegister.resetIdentity()
                    val r = DeviceRegister.registerNow(HttpClientProvider.get())
                    AppLog.d("PlayerActivity", "重试：新身份 device_id=" + r.deviceId)
                } catch (t: Throwable) {
                    // 注册失败也不拦重播：playCurrent 里的 ensureRegistered 会给出
                    // 冷却中/网络错误等可读提示，60s 冷却天然防止狂点形成注册风暴。
                    AppLog.e("PlayerActivity", "重试：换新身份失败，仍按原路径重播", t)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            errorRetry.isEnabled = true
            errorRetry.text = getString(R.string.error_retry)
            errorRetry.visibility = View.GONE
            errorPanel.visibility = View.GONE
            playCurrent()
        }
    }

    /* ==================== 覆盖层显示 ==================== */

    /**
     * 显示顶部信息条 + 底部进度条（都不是焦点控件，不会抢焦点）。
     *
     * 用户诉求：边栏**只在暂停或拖动进度条时**出现，播放中不再弹出。
     * 这里改成「常驻」语义：暂停态保持可见，直到恢复播放（onIsPlayingChanged
     * 里统一收起）；拖动进度条期间由 tracking 保活，松手恢复播放即收。
     */
    private fun showControls() {
        topBar.visibility = View.VISIBLE
        controlBar.visibility = View.VISIBLE
        updateProgressText()
        // 播放中的调用（如快进提示）不应让边栏长驻：仍挂自动隐藏。
        if (player?.isPlaying == true) {
            handler.removeCallbacks(hideControlsRunnable)
            handler.postDelayed(hideControlsRunnable, 3000)
        }
    }

    private val hideControlsRunnable = Runnable {
        // 暂停/拖动期间边栏必须常驻；到点时若还在拖动则不隐藏（tracking 保活）。
        if (tracking) return@Runnable
        if (player?.isPlaying == false) return@Runnable
        topBar.visibility = View.GONE
        controlBar.visibility = View.GONE
    }

    /** 居中短暂提示（快进/快退/切集/开关状态）。 */
    private fun toast(msg: String) {
        toastHint.text = msg
        toastHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideToastRunnable)
        handler.postDelayed(hideToastRunnable, 1200)
    }

    private val hideToastRunnable = Runnable { toastHint.visibility = View.GONE }

    private fun updateProgressText() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        positionText.text = formatMs(pos)
        durationText.text = if (dur > 0) formatMs(dur) else "--:--"
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** dp → px（选集弹窗动态建格子用）。 */
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    /* ==================== 菜单 ==================== */

    private fun toggleMenu() {
        if (menuPanel.visibility == View.VISIBLE) {
            hideMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        menuPanel.visibility = View.VISIBLE
        fitMenuPanel()
        updateAspectLabel()
        updateRotateLabel()
        updateQualityLabel()
        updateSpeedLabel()
        updateDanmakuLabel()
        updateDecoderLabel()
        // 触屏设备不抢焦点，避免菜单首项出现红框高亮（TV 需焦点才能用遥控器选择）
        if (UiScale.isTv(this)) menuFill.post { menuFill.requestFocus() }
    }

    /**
     * 手机横屏（翻转看横版画幅短剧）时屏幕高度只剩约 390dp，菜单 8 项
     * （触控下限 48dp + 间距）约 540dp 会溢出到屏幕外，底部「退出播放」点不到。
     * 这里给面板限高到可用区域，让外层 ScrollView 承担滚动。
     *
     * 只在手机横屏生效：TV 是 1280×720dp 画布（面板约 540dp 放得下）、
     * 手机竖屏高度约 870dp，都无需限高，保持 wrap_content 与旧版行为一致。
     */
    private fun fitMenuPanel() {
        val lp = menuPanel.layoutParams as FrameLayout.LayoutParams
        val landscapePhone = !UiScale.isTv(this) &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 屏高(px) - 顶部 72dp - 底部留 12dp；下限 180dp 保证至少能看到几项
        val want = if (landscapePhone) {
            (resources.displayMetrics.heightPixels - dp(84)).coerceAtLeast(dp(180))
        } else FrameLayout.LayoutParams.WRAP_CONTENT
        if (lp.height != want) {
            lp.height = want
            menuPanel.layoutParams = lp
        }
    }

    /**
     * 翻转不改 configChanges 声明（manifest 已含 orientation|screenSize），
     * Activity 不重建、播放不中断，但面板尺寸要按新方向重新适配。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (menuPanel.visibility == View.VISIBLE) fitMenuPanel()
        if (::epPickerRoot.isInitialized && epPickerRoot.visibility == View.VISIBLE) fitEpPanel()
    }

    /** 选集面板同样限高（手机横屏 20 格网格 + 控制行远超横屏高度）。 */
    private fun fitEpPanel() {
        val panel = findViewById<View>(R.id.ep_picker_panel)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        val landscapePhone = !UiScale.isTv(this) &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val want = if (landscapePhone) {
            (resources.displayMetrics.heightPixels - dp(16)).coerceAtLeast(dp(200))
        } else FrameLayout.LayoutParams.WRAP_CONTENT
        if (lp.height != want) {
            lp.height = want
            panel.layoutParams = lp
        }
    }

    private fun hideMenu() {
        menuPanel.visibility = View.GONE
        // 菜单隐藏后焦点会留在已 GONE 的按钮上，TV 上会导致"方向键全无反应"。
        // 把焦点交给隐形锚点：锚点不消费任何按键，全部按键仍走 Activity.onKeyDown。
        findViewById<View>(R.id.focus_anchor).post {
            try { findViewById<View>(R.id.focus_anchor).requestFocus() } catch (_: Exception) {}
        }
    }

    /* ==================== 起播 loading（无感切集） ==================== */

    /** loading 是否仍在等待显示（hideLoading 后置 false，防止延迟任务补弹） */
    private var loadingPending = false
    private val showLoadingRunnable = Runnable {
        if (loadingPending) progress.visibility = View.VISIBLE
    }
    private val hideLoadingRunnable = Runnable { hideLoading() }

    /** 起播开始：延迟 [LOADING_SHOW_DELAY_MS] 再显示转圈，快的切集全程不闪。 */
    private fun scheduleLoading() {
        loadingPending = true
        handler.removeCallbacks(showLoadingRunnable)
        handler.postDelayed(showLoadingRunnable, LOADING_SHOW_DELAY_MS)
    }

    /** 出首帧 / 就绪 / 失败时收起转圈（幂等）。 */
    private fun hideLoading() {
        loadingPending = false
        handler.removeCallbacks(showLoadingRunnable)
        handler.removeCallbacks(hideLoadingRunnable)
        progress.visibility = View.GONE
    }

    /* ==================== 进度刷新（500ms） ==================== */

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player
            // 切集准备期播放器里仍是上一集的内容：进度/弹幕/历史都不能按新集算，
            // 等 STATE_READY 复位 switchingEpisode 后自然恢复。
            if (p != null && !tracking && !switchingEpisode) {
                val dur = p.duration
                val pos = p.currentPosition
                if (dur > 0) {
                    seekBar.max = 1000
                    seekBar.progress = (pos * 1000 / dur).toInt()
                    updateProgressText()
                    // 兜底预取：正常在 STATE_READY 就已经开始预取，这里只在
                    // 之前那轮预取被切集取消/失败时补跑（窗口内有缺就补）。
                    // 放在这里而非弹幕函数内，避免「关闭弹幕导致预加载一并失效」。
                    if (pos >= dur / 5) prefetchNext()
                }
                updateDanmaku(p, pos, dur)
                // 周期性落盘进度（节流 10s），保证异常退出也不丢进度
                recordHistory(force = false)
            }
            handler.postDelayed(this, 500)
        }
    }

    /* ==================== 按键 ==================== */

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 选集弹窗展开时：返回/菜单关闭，其余方向键+确定交给系统做焦点导航
        if (epPickerRoot.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> { closeEpisodePicker(); return true }
                KeyEvent.KEYCODE_BACK -> { closeEpisodePicker(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
        }
        // 菜单展开时：只接管菜单键/返回键，方向键留给系统做焦点导航
        if (menuPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> { hideMenu(); return true }
                KeyEvent.KEYCODE_BACK -> { hideMenu(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
        }

        // 失败提示带「重试」时：遥控器确定键 = 换新身份重播（错误态下确定键本来的
        // 「暂停/继续」没有意义）。按钮不参与焦点导航，上下键切集等语义保持不变。
        if (errorPanel.visibility == View.VISIBLE && errorRetry.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    retryWithNewIdentity()
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 手机：单击返回直接退出（手机常见交互）；TV：双击退出，与电视应用一致。
                if (UiScale.isTv(this)) onBackPressed2x() else finish()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showMenu()
                return true
            }
            // 遥控器确定键：直接暂停/继续播放（用户诉求）
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            // 左右键：快退 / 快进。长按会持续收到 repeatCount>0 的重复事件，
            // 步长随按住时长线性放大（15s → 30s → 45s …），实现"长按叠加时长"。
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                quickSeek(-1, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                quickSeek(1, event.repeatCount)
                return true
            }
            // 上下键：上一集 / 下一集
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchEpisode(currentPos - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchEpisode(currentPos + 1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                recordHistory(force = true)
                showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                // 切集准备期不允许把上一集续播起来
                if (!switchingEpisode) {
                    player?.play()
                    // 恢复播放：立即收起边栏（onIsPlayingChanged 也会兜一遍）
                    hideControlsNow()
                }
                return true
            }
            else -> {
                // 其余按键（含未知键）不再弹边栏：用户诉求是只有暂停/拖进度条才显示。
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 立即隐藏边栏（恢复播放时调用，不等自动隐藏定时器）。 */
    private fun hideControlsNow() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!tracking) {
            topBar.visibility = View.GONE
            controlBar.visibility = View.GONE
        }
    }

    /**
     * 快进/快退。
     * @param dir -1 快退，+1 快进
     * @param repeatCount 系统长按重复计数；0 表示首次按下，步长取设置页的 seek_step（默认 15s）；
     *                    每次重复步长递增一个基础步长，让长按越久跳得越多。
     */
    private fun quickSeek(dir: Int, repeatCount: Int) {
        val p = player ?: return
        // 切集准备期：播放器里还是上一集，快进快退不该作用在旧集上
        if (switchingEpisode) return
        val dur = p.duration
        if (dur <= 0) return
        // 用户已手动操作进度：作废待执行的续播 seek，避免 STATE_READY 回调
        // 把用户刚拖到的位置又覆盖回续播点（READY 晚于按键到达的竞态）。
        pendingResumeMs = 0L
        val step = seekStepMs * (1 + repeatCount.coerceAtMost(MAX_SEEK_REPEAT).toLong())
        val target = (p.currentPosition + dir * step).coerceIn(0L, dur)
        p.seekTo(target)
        skipSeekGroup(playGen)
        danmakuView.setPositionMs(target)
        recordHistory(force = true)
        // 快进/快退 = 遥控器上的「拉进度条」：短暂显示边栏（播放中 3 秒后自动收起），
        // 让用户看清落点；不按暂停/快进时边栏保持隐藏。
        showControls()
        toast(if (dir > 0) getString(R.string.seek_forward, (step / 1000).toString() + "s")
        else getString(R.string.seek_backward, (step / 1000).toString() + "s"))
    }

    /**
     * 位置发生大幅跳变（快进快退 / 拖动进度条）后同步附属状态。
     *
     * ① 清掉弹幕「已拉取窗口」标记：跳转后旧窗口的判定不再成立，不清会导致
     *    跳到新位置后迟迟不出弹幕（要等下一个 30s 自然窗口才拉）。
     * ② 弹幕视图重基准在途弹幕（DanmakuView.setPositionMs 内部会识别位置跳变）。
     * ③ 落盘一次历史，避免跳转后立刻退出丢失进度。
     */
    private fun skipSeekGroup(gen: Int) {
        if (gen != playGen) return
        danmakuFetchedWindows.clear()
        danmakuAttempts.clear()
        danmakuRetryAt.clear()
        lastDanmakuWindow = -1L
    }

    /* ==================== 退出 ==================== */

    /**
     * 双击返回退出：第一次按给出提示，[EXIT_INTERVAL_MS] 内再按一次才真正退出。
     * 与主流电视应用（爱奇艺/腾讯视频 TV 版等）一致，避免「按一下返回就退出」的误操作。
     */
    private fun onBackPressed2x() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressed < EXIT_INTERVAL_MS) {
            finish()
        } else {
            lastBackPressed = now
            toast(getString(R.string.press_again_to_exit))
        }
    }

    /**
     * 退出确认对话框（仅菜单里的「退出播放」入口使用）。
     *
     * 返回键不走这里（手机单击 / TV 双击退出）。原设 cancelable(false) 导致部分 TV ROM 上
     * 对话框按钮无初始焦点时返回键被吞，用户被困在播放页；保持 cancelable(true) 并在
     * onShow 显式聚焦「继续播放」。
     */
    /* ==================== 选集弹窗 ==================== */

    /** 每页集数：5 列 × 4 行 = 20（与参考图一致）。 */
    private val EP_PAGE_SIZE = 20
    private val EP_COLUMNS = 5

    /** 打开选集弹窗：定位到当前集所在页，暂停播放并抢焦点。 */
    private fun openEpisodePicker() {
        if (vidList.isEmpty()) return
        hideMenu()
        // 弹窗是独立焦点域：打开时暂停，避免用户在选集时声音继续
        tracking = false
        val p = player
        p?.pause()
        // 当前集所在页起始下标
        epGroupStart = (currentPos / EP_PAGE_SIZE) * EP_PAGE_SIZE
        buildEpisodeGrid()
        epPickerRoot.visibility = View.VISIBLE
        // 手机横屏时面板内容高于屏幕：限高后由 ScrollView 滚动（竖屏/TV 不限）
        fitEpPanel()
        // 焦点落到当前集格子（若在本页），否则落到网格第一个
        epGrid.post {
            val cur = epCells.getOrNull(currentPos - epGroupStart)
            (cur ?: epCells.firstOrNull())?.requestFocus()
        }
    }

    private fun closeEpisodePicker() {
        epPickerRoot.visibility = View.GONE
        findViewById<View>(R.id.focus_anchor).post {
            try { findViewById<View>(R.id.focus_anchor).requestFocus() } catch (_: Exception) {}
        }
        showControls()
    }

    /** 生成/刷新网格：复用 epCells，填充当前页的集数按钮。 */
    private fun buildEpisodeGrid() {
        val total = vidList.size
        epPickerTitle.text = getString(R.string.ep_picker_title) + " / " +
                getString(R.string.ep_picker_subtitle, currentEpisode())
        val pageEnd = minOf(epGroupStart + EP_PAGE_SIZE, total)
        epPickerRange.text = getString(
                R.string.ep_picker_range,
                epGroupStart + 1, pageEnd, total)

        // 确保格子数量够一页
        while (epCells.size < EP_PAGE_SIZE) {
            val tv = TextView(this)
            tv.setTextColor(resources.getColor(R.color.text_primary))
            tv.textSize = 15f
            tv.gravity = android.view.Gravity.CENTER
            tv.background = resources.getDrawable(R.drawable.ep_cell_bg)
            tv.isFocusable = true
            // 手机触摸端不设 focusableInTouchMode（否则第一次点击只聚焦不切集）
            tv.isFocusableInTouchMode = UiScale.isTv(this)
            tv.isClickable = true
            val lp = GridLayout.LayoutParams()
            if (UiScale.isTv(this)) {
                lp.width = dp(90)
                // 靠 GridLayout columnCount=5 自动流式换行，格子固定宽，避免 weight 布局踩坑
            } else {
                // 手机竖屏：格子等宽填满面板（4 列），宽度交给列权重分配
                lp.width = 0
                lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            lp.height = dp(48)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            tv.layoutParams = lp
            epGrid.addView(tv)
            epCells.add(tv)
        }
        epGrid.removeAllViews()

        for (i in 0 until EP_PAGE_SIZE) {
            val tv = epCells[i]
            val idx = epGroupStart + i
            if (idx < total) {
                tv.text = (epList.getOrNull(idx) ?: (idx + 1)).toString()
                tv.visibility = View.VISIBLE
                // 当前集高亮（selected 态）
                tv.isSelected = (idx == currentPos)
                tv.setOnClickListener {
                    closeEpisodePicker()
                    switchEpisode(idx)
                }
            } else {
                tv.visibility = View.INVISIBLE
                tv.setOnClickListener(null)
            }
            epGrid.addView(tv)
        }
        // 上一组/下一组可用性
        epBtnPrev.isEnabled = epGroupStart > 0
        epBtnPrev.alpha = if (epGroupStart > 0) 1f else 0.4f
        epBtnNext.isEnabled = pageEnd < total
        epBtnNext.alpha = if (pageEnd < total) 1f else 0.4f
    }

    /** 切换上/下一页（组）。 */
    private fun switchEpGroup(dir: Int) {
        val total = vidList.size
        var start = epGroupStart + dir * EP_PAGE_SIZE
        if (start < 0) start = 0
        if (start >= total) start = (total - 1) / EP_PAGE_SIZE * EP_PAGE_SIZE
        if (start == epGroupStart) return
        epGroupStart = start
        buildEpisodeGrid()
        epCells.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
    }

    /** 跳转集数：输入框弹窗，输入目标集号后按下标切集。 */
    private fun showJumpDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.ep_picker_jump_hint)
        val pad = dp(24)
        val container = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(pad, dp(8), pad, 0)
        input.layoutParams = lp
        container.addView(input)
        val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.ep_picker_jump)
                .setView(container)
                .setPositiveButton("跳转") { _, _ ->
                    val n = input.text.toString().toIntOrNull()
                    if (n != null) jumpToEpisode(n)
                }
                .setNegativeButton("取消", null)
                .create()
        dialog.setOnShowListener {
            try { input.requestFocus() } catch (_: Exception) {}
        }
        dialog.show()
    }

    /** 按集号（epList 里的显示值）跳转；找不到则提示。 */
    private fun jumpToEpisode(epNumber: Int) {
        val idx = epList.indexOf(epNumber).let {
            if (it >= 0) it else if (epNumber in 1..vidList.size) epNumber - 1 else -1
        }
        if (idx < 0 || idx >= vidList.size) {
            toast(getString(R.string.ep_picker_jump_invalid))
            return
        }
        closeEpisodePicker()
        switchEpisode(idx)
    }

    private fun showExitDialog() {
        tracking = false
        val p = player
        val wasPlaying = p?.isPlaying == true
        p?.pause()
        recordHistory(force = true)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("继续播放还是退出？")
            .setPositiveButton("继续播放") { _, _ ->
                if (wasPlaying) {
                    player?.play()
                    hideControlsNow()
                } else {
                    showControls()
                }
            }
            .setNegativeButton("退出播放") { _, _ -> finish() }
            .setCancelable(true)
            .create()
        // 返回键关闭对话框 = 取消退出：恢复播放，避免停在暂停态让用户以为卡死
        dialog.setOnCancelListener { if (wasPlaying) player?.play() }
        // TV 上部分 ROM 不给对话框按钮初始焦点，显示后显式聚焦「继续播放」
        dialog.setOnShowListener {
            try {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            } catch (_: Exception) {
            }
        }
        dialog.show()
    }

    /* ==================== 生命周期 ==================== */

    override fun onStop() {
        super.onStop()
        // 用户反馈：按主页返回后后台还在出声音。Activity 不可见时必须暂停。
        // shouldBePlaying 记录「用户意图」，回前台据此决定是否自动续播——
        // 避免把用户手动暂停的播放强行续起。
        // 切集准备期播放器里还是上一集：本次 onStop 属于旧集，不落盘、不记恢复位。
        if (switchingEpisode) return
        shouldBePlaying = player?.isPlaying == true
        resumePosition = try {
            val p = player
            if (p != null && p.duration > 0) p.currentPosition.toInt() else 0
        } catch (_: Exception) {
            0
        }
        recordHistory(force = true)
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        val p = player ?: return
        // 切集准备期被切到后台：不做 seek/续播，等新集 STATE_READY 后自然起播
        if (switchingEpisode) return
        // 从后台/按主页返回后回前台：回到原位并仅在「用户意图是播放」时续播
        if (resumePosition > 0) {
            try {
                if (p.duration > 0) p.seekTo(resumePosition.toLong())
            } catch (_: Exception) {
            }
            resumePosition = 0
        }
        if (shouldBePlaying && p.playbackState != Player.STATE_IDLE) {
            p.play()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退出/切走前把进度落盘（onDestroy 不保证一定执行）
        recordHistory(force = true)
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        recordHistory(force = true)
        try { player?.release() } catch (_: Exception) {}
        player = null
        cleanupClearFiles()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
        /** 起始位置下标（0-based），非集号 */
        const val EXTRA_EPISODE_POS = "episode_pos"
        const val EXTRA_VID_LIST = "vid_list"
        /** 与 vid_list 按下标一一对应的集号列表 */
        const val EXTRA_EP_LIST = "ep_list"

        /** 播放偏好存储（SettingsActivity 与播放页共用，s6 由播放页读取） */
        const val PREFS = "player_prefs"
        const val PREF_ASPECT = "aspect_mode"
        /** 手机播放页「翻转」：true = 横屏播放（横版画幅短剧） */
        const val PREF_LANDSCAPE = "player_landscape"
        const val PREF_QUALITY = "quality_mode"
        const val PREF_SPEED = "playback_speed"
        const val PREF_DANMAKU = "danmaku_enabled"
        const val PREF_DECODER_SOFT = "decoder_soft"
        /** 遥控快进/快退基础步长（ms），设置页可调，默认 15 秒 */
        const val PREF_SEEK_STEP = "seek_step"
        /** 播完一集是否自动进入下一集 */
        const val PREF_AUTO_NEXT = "auto_next"
        /** 弹幕窗口长度（ms） */
        private const val DANMAKU_WINDOW_MS = 30000L

        /** 弹幕失败窗口重试：最多次数与退避间隔（D-08） */
        private const val DANMAKU_MAX_ATTEMPTS = 3
        private const val DANMAKU_RETRY_DELAY_MS = 5000L

        /** 快进退基础步长：15 秒 */
        private const val QUICK_SEEK_MS = 15_000L
        /** 长按叠加的最大重复级数（避免一次跨太多） */
        private const val MAX_SEEK_REPEAT = 7

        /** 判为"真的播完"的容差：位置需在片尾 1.5s 内 */
        private const val END_TOLERANCE_MS = 1500L
        /** 续播的最小进度：小于 5s 视为从头看 */
        private const val RESUME_MIN_MS = 5_000L
        /**
         * 续播的片尾余量：上次位置距片尾不足该值（或时长 10%，取小）时视为已看完，
         * 从头播。若只留几秒余量，用户续播进去还没看清就 STATE_ENDED 自动跳集，
         * 观感仍是"点进去就跳下一集"（用户反馈的恶性 bug 场景）。
         */
        private const val RESUME_TAIL_MARGIN_MS = 15_000L
        /** 播放中写观看历史的节流间隔 */
        private const val HISTORY_WRITE_INTERVAL_MS = 10_000L

        /** 双击返回退出的时间窗口：2 秒内连按两次返回键直接退出 */
        private const val EXIT_INTERVAL_MS = 2_000L

        /** 手机端上下滑切集：判定为纵向滑动的最小位移（dp） */
        private const val SWIPE_SLOP_DP = 24

        /** 手机端上下滑切集：真正触发切集的位移阈值（dp） */
        private const val SWIPE_TRIGGER_DP = 80

        /**
         * 切集抑制兜底时限：正常起播（含下载+解密）远快于此。
         * 到点仍未收到 STATE_READY/onPlayerError 就强制解除，保证上下键不锁死。
         */
        private const val SWITCH_GUARD_MS = 45_000L

        /**
         * 起播 loading 的延迟显示时长：本地缓存命中通常 200~400ms 就出首帧，
         * 立刻弹转圈会让「无感切集」闪一下，反而割裂。
         */
        private const val LOADING_SHOW_DELAY_MS = 400L

        /**
         * 起播 loading 的兜底隐藏时长：个别 ROM 不回调 onRenderedFirstFrame，
         * 到点强制收起，避免转圈永远挂在画面上。
         */
        private const val FIRST_FRAME_HIDE_MS = 8_000L

        /** tkhd 解析时读取的文件头长度（moov 通常在前 256KB 内）。 */
        private const val MOOV_PROBE_BYTES = 256 * 1024

        /**
         * 预加载窗口：提前把当前集之后 N 集下载解密进缓存，实现丝滑切集
         * （含选集弹窗任意跳转）。单集约 5~11MB，N=5 时峰值占用约 50MB，
         * 低内存盒子可接受；再大收益递减且挤占存储。
         */
        private const val PREFETCH_AHEAD = 5

        /**
         * 媒体 CDN 限流（HTTP 429）后的预加载冷却时长。
         * 线上日志显示限流窗口约 1~3 分钟：冷却期内一条预加载请求都不发，
         * 到期后清空失败标记重新预取，让窗口自然结束。
         */
        private const val PREFETCH_COOLDOWN_MS = 90_000L

        /**
         * 起播总时间预算（R-01，用户已拍板）。用户在屏幕前等起播，
         * 下载+退避的总耗时封顶：超时放弃剩余变体早报错，提示可点重试，
         * 而不是转圈几分钟。预加载是后台行为，不受此预算限制。
         */
        private const val STARTUP_MEDIA_BUDGET_MS = 30_000L

        /** 倍速档位 */
        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 画幅模式：适应（默认，保持比例）/ 拉伸 / 原始 / 填充 */
        const val ASPECT_FIT = 0
        const val ASPECT_STRETCH = 1
        const val ASPECT_ORIGINAL = 2
        const val ASPECT_FILL = 3

        private enum class AspectMode(val labelRes: Int) {
            FIT(R.string.aspect_fit),
            STRETCH(R.string.aspect_stretch),
            ORIGINAL(R.string.aspect_original),
            FILL(R.string.aspect_fill)
        }

        private val ASPECT_MODES = AspectMode.values()

        /**
         * 清晰度档位。`capHeight` 为传给取流接口的像素高上限：
         *  - `-1` = 自动，按设备能力判定（低端设备 720，其余不限）
         *  - `0`  = 原画，不限制（取服务端最高档）
         * 其余为显式上限，服务端从变体列表里挑「不超过该值的最优档」；
         * 若该剧没有对应档位，会自动回退到最接近的档（不会无源可播）。
         */
        private enum class QualityMode(val labelRes: Int, val capHeight: Int) {
            AUTO(R.string.quality_auto, -1),
            P480(R.string.quality_480, 480),
            P720(R.string.quality_720, 720),
            P1080(R.string.quality_1080, 1080),
            ORIGIN(R.string.quality_origin, 0)
        }

        private val QUALITY_MODES = QualityMode.values()

        /** 「自动」档下标（默认档）。 */
        const val QUALITY_AUTO = 0
    }
}
