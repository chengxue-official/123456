package com.houg.tv

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 全局 density 缩放（自适应方案 L1）：把所有设备统一到 1280×720dp 设计画布。
 *
 * 缩放系数 k = min(w/1280, h/720)，覆盖 densityDpi = round(160 × k)。
 * 好处：布局/dimens/字号不再随分辨率与系统 densityDpi 漂移，
 * 1080p、4K、540p 老盒子、手机横屏拿到的都是同一块 dp 画布。
 */
object UiScale {
    private const val BASE_W = 1280f
    private const val BASE_H = 720f

    /** UI 形态覆盖值：auto=自动检测 / tv=强制电视 / phone=强制手机（首页兜底按钮写） */
    const val MODE_AUTO = "auto"
    const val MODE_TV = "tv"
    const val MODE_PHONE = "phone"

    private const val PREFS = "houg_ui"
    private const val KEY_UI_MODE = "ui_mode"

    /** 覆盖值缓存：isTv 会被 softenTouchFocus 逐 View 高频调用，不能每次读 prefs */
    private var cachedMode: String? = null
    private var cachedIsTv = false

    /** 当前 UI 形态覆盖（首页兜底按钮读/写，持久化；重启后仍生效） */
    fun uiMode(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UI_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setUiMode(context: Context, mode: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_UI_MODE, mode).apply()
        cachedMode = null // 下次 isTv 按新覆盖重新判定
    }

    /**
     * 是否按 TV 形态渲染。优先读手动覆盖（首页兜底按钮），覆盖为 auto 时自动检测。
     * 注意：不能用 configuration.uiMode 直接判断——AppCompat 重建 Resources 时会把
     * warp 强设的 uiMode 打回系统值（模拟器调试后门因此失效）。
     */
    fun isTv(context: Context): Boolean {
        val mode = uiMode(context)
        if (mode != cachedMode) {
            cachedMode = mode
            cachedIsTv = when (mode) {
                MODE_TV -> true
                MODE_PHONE -> false
                else -> autoDetect(context)
            }
        }
        return cachedIsTv
    }

    /**
     * 自动判定设备形态（进程内稳定）。
     *
     * 线上实测（VIDAA_TV，海信 VIDAA 系统，Android 11）：VIDAA 不是 Android TV，
     * 不带 leanback feature、还带 touchscreen feature，只靠 leanback/无触摸屏
     * 两个判据会被判成手机 → 整个 App 变手机 UI 竖屏。补三道判据：
     * ① FEATURE_TELEVISION（老 Android TV feature）；
     * ② 系统 uiMode 为 TELEVISION（AppCompat 打回的是 warp 强设值，系统真实值可靠）；
     * ③ 无蜂窝网络 + 屏幕短边 ≥ 600dp——电视/盒子无通话模块，1080p 电视短边 720dp；
     *    手机短边 320~440dp 且都有 telephony。
     * 仍判不出的设备（540p 杂牌盒子等）用首页顶部兜底按钮手动切换。
     */
    private fun autoDetect(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true
        val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        // 调试后门（仅可调试包生效）：手机模拟器验 TV 态用
        //   adb shell settings put global houg_tvui 1
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try {
                if (Settings.Global.getInt(context.contentResolver, "houg_tvui", 0) == 1) return true
            } catch (_: Exception) {
            }
        }
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            val dm = context.resources.displayMetrics
            val shortDp = min(dm.widthPixels, dm.heightPixels) / dm.density
            if (shortDp >= 600f) return true
        }
        return false
    }

    /** 页面左右安全边距（TV overscan 5%：64dp；手机 24dp），代码侧按 isTv 选值 */
    fun pageH(context: Context): Int = px(context, R.dimen.page_h, R.dimen.page_h_tv)

    /** 页面顶部安全边距（TV 36dp；手机 14dp） */
    fun pageTop(context: Context): Int = px(context, R.dimen.page_top, R.dimen.page_top_tv)

    /** 网格卡片间距（TV 14dp；手机 12dp） */
    fun gapPx(context: Context): Int = px(context, R.dimen.grid_gap, R.dimen.grid_gap_tv)

    /** 字号增益系数（TV 1.05；手机 1.0） */
    fun fontBoost(context: Context): Float =
        context.resources.getFloat(if (isTv(context)) R.dimen.font_boost_tv else R.dimen.font_boost)

    /** 封面卡片目标格宽（TV 150dp / 手机 110dp：411dp 屏宽约出 3 列） */
    fun coverWidthDp(context: Context): Float = if (isTv(context)) 150f else 110f

    /** 选集格目标宽（TV 100dp / 手机 88dp） */
    fun episodeWidthDp(context: Context): Float = if (isTv(context)) 100f else 88f

    private fun px(context: Context, phone: Int, tv: Int): Int =
        context.resources.getDimensionPixelSize(if (isTv(context)) tv else phone)

    /**
     * 在 Activity.attachBaseContext 里包一层：覆盖 density 与字号。
     * 手机/TV 的安全边距、网格间距差异不在 warp 里做（AppCompat 会剥 uiMode，
     * 无法靠 values-television 限定符），由 pageH/gapPx 等代码侧选值。
     */
    fun warp(base: Context): Context {
        // 手机竖屏刷短剧：直接沿用系统 density（如 411×914dp）。
        // 1280×720 画布公式是横屏 16:9 假设，竖屏 1080×2400 下
        // min(1080/1280, 2400/720)=0.84 会得到 1280dp 宽的离谱画布（主界面算成 8 列）。
        if (!isTv(base)) return base
        val cfg = Configuration(base.resources.configuration)
        val dm = base.resources.displayMetrics
        // k 只由物理分辨率决定：长边对 BASE_W、短边对 BASE_H，与 attach 时的窗口方向无关。
        // attachBaseContext 先于 applyOrientation 执行（窗口还带着原方向），若按当前窗口
        // 宽高算 k，竖屏 attach（手机上手动切 TV / 后门触发）会算出 0.84 的错误密度，
        // 且 manifest 声明 configChanges 不重建，转横屏后 density 永不重算 → 整个 TV UI 缩水。
        val longPx = max(dm.widthPixels, dm.heightPixels).toFloat()
        val shortPx = min(dm.widthPixels, dm.heightPixels).toFloat()
        val k = min(longPx / BASE_W, shortPx / BASE_H)
        if (k > 0f) {
            cfg.densityDpi = (160f * k).roundToInt().coerceIn(120, 640)
        }
        val ctx = base.createConfigurationContext(cfg)
        // 字号：density × 系统字体缩放 × TV 增益（fontBoost 按 isTv 选值）
        ctx.resources.displayMetrics.scaledDensity =
            ctx.resources.displayMetrics.density * cfg.fontScale * fontBoost(ctx)
        return ctx
    }

    /**
     * 屏幕方向：TV 横屏（遥控器 + 电视摆放），手机竖屏（刷短剧形态）。
     * manifest 不声明方向（跟随设备自然方向），这里在 onCreate 明确一次：
     * 以前 manifest 写死 landscape 会让手机进页面先按横屏创建一帧再转竖屏（闪横屏 + 首帧测量异常）。
     * 注意不要放到 attachBaseContext：那时 Activity 还没进入可设方向的窗口状态。
     */
    fun applyOrientation(activity: Activity) {
        activity.requestedOrientation =
            if (isTv(activity)) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /**
     * 全屏沉浸 + 方向，所有页面的统一入口。
     * 方向按设备（TV 横屏 / 手机竖屏），同时隐藏状态栏与导航栏，
     * 保证手机竖屏刷短剧时画面顶到屏幕边缘（和主界面观感一致）。
     */
    fun applyImmersive(activity: Activity) {
        applyOrientation(activity)
        val decor = activity.window.decorView
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 19) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        decor.systemUiVisibility = flags
    }

    /**
     * 触摸设备（手机）：把 focusableInTouchMode 控件降级为普通可点击控件。
     *
     * 触摸模式下 focusableInTouchMode=true 且当前未聚焦时，View.onTouchEvent 在 ACTION_UP
     * 会先 requestFocus 并跳过 performClick（focusTaken 分支），现象就是"第一次点只变高亮、
     * 要再点一次才响应"。手机没有遥控器焦点导航，直接降级；TV 保持原样不动。
     * EditText 例外：它必须靠 focusableInTouchMode 才能点进输入框。
     */
    fun softenTouchFocus(root: View) {
        if (isTv(root.context)) return
        if (root is EditText) return
        if (root.isFocusableInTouchMode) root.isFocusableInTouchMode = false
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) softenTouchFocus(root.getChildAt(i))
        }
    }

    /** 已挂过 item attach 监听的 RecyclerView（弱引用，避免树被反复 attach 时监听器重复叠加） */
    private val hookedRecyclers = java.util.WeakHashMap<RecyclerView, Unit>()

    /**
     * 手机端触控目标：把固定高度不足 48dp 的可交互控件（clickable / focusable）抬到 48dp。
     *
     * TV 不生效：遥控器靠 2dp 焦点描边定位，1280×720dp 画布上原型比例已足够大。
     * 手机上没有指针悬停，32–44dp 的返回 / 工具按钮、筛选胶囊、集格低于 Material 48dp
     * 触控标准，误触率高。只改 layoutParams 的固定高度（不动 XML），TV 观感零变化；
     * 方形图标按钮（40×40）同步加宽，保持可点区域为正方形。
     */
    fun enforceTouchTargets(root: View, minDp: Int = 48) {
        if (isTv(root.context)) return
        val minPx = (minDp * root.resources.displayMetrics.density).roundToInt()
        raiseTouchTree(root, minPx)
    }

    /** 递归抬升静态控件；RecyclerView 的 item 由 adapter 动态 inflate，挂 attach 监听逐个抬。 */
    private fun raiseTouchTree(root: View, minPx: Int) {
        raiseTouchTarget(root, minPx)
        if (root is RecyclerView && hookedRecyclers.put(root, Unit) == null) {
            root.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        // attach 发生在测量/布局过程中，直接改 lp 会打断本帧，post 到下一帧再抬
                        view.post { raiseTouchTree(view, minPx) }
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                }
            )
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) raiseTouchTree(root.getChildAt(i), minPx)
        }
    }

    private fun raiseTouchTarget(v: View, minPx: Int) {
        if (!v.isClickable && !v.isFocusable) return
        val lp = v.layoutParams ?: return
        if (lp.height in 1 until minPx) {
            val oldHeight = lp.height
            lp.height = minPx
            if (lp.width == oldHeight) lp.width = minPx
            v.layoutParams = lp
        }
    }
}

/** 网格规划结果：列数 + 每列宽度（px） */
data class GridPlan(val columns: Int, val itemWidthPx: Int)

/**
 * L2：按可用宽度算「列数 + 每列宽」。
 * n = (avail + gap) / (target + gap)，itemW = (avail - gap×(n-1)) / n。
 */
fun gridPlan(availPx: Int, gapPx: Int, targetPx: Int, min: Int = 2, max: Int = 12): GridPlan {
    if (availPx <= 0 || targetPx <= 0) return GridPlan(min, 0)
    val n = ((availPx + gapPx) / (targetPx + gapPx)).coerceIn(min, max)
    val item = ((availPx - gapPx * (n - 1)) / n).coerceAtLeast(1)
    return GridPlan(n, item)
}

/**
 * 网格间距：列间/行间统一 gap，首列/首行不加（贴齐 recycler 的页面边距）。
 * 配合 item 布局 width=match_parent、无 margin 使用，格宽由列数均分决定。
 */
class GridSpacingDecoration(private val gapPx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val lm = parent.layoutManager as? GridLayoutManager ?: return
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        outRect.left = if (pos % lm.spanCount == 0) 0 else gapPx
        outRect.top = if (pos < lm.spanCount) 0 else gapPx
    }
}

/**
 * 给 RecyclerView 装配「自动列数 + 自动格宽 + 统一间距」的网格。
 * 布局完成后再计算（能拿到真实可用宽：recycler 宽 - 左右 padding）。
 *
 * @param targetItemWidthDp 目标格宽；列数 = 可用宽能塞下几个
 */
fun installAutoGrid(
    recycler: RecyclerView,
    targetItemWidthDp: Float,
    minColumns: Int = 2,
    maxColumns: Int = 12
) {
    var lastColumns = 0
    val recompute = {
        val avail = recycler.width - recycler.paddingLeft - recycler.paddingRight
        if (avail > 0) {
            val gapPx = UiScale.gapPx(recycler.context)
            val targetPx = (targetItemWidthDp * recycler.resources.displayMetrics.density).roundToInt()
            val plan = gridPlan(avail, gapPx, targetPx, minColumns, maxColumns)
            // 列数变化、或当前没有 layoutManager（横竖屏切换首帧 / 列表被重新挂载）
            // 都要重新装配，否则会把横屏测出的列数留在竖屏上，甚至整片空白。
            if (plan.columns != lastColumns || recycler.layoutManager == null) {
                lastColumns = plan.columns
                recycler.layoutManager = GridLayoutManager(recycler.context, plan.columns)
            }
        }
    }
    recycler.addItemDecoration(GridSpacingDecoration(UiScale.gapPx(recycler.context)))
    // 首次布局 + 之后每次尺寸变化都重算：手机冷启动时窗口可能先按横屏测量、
    // 再由 requestedOrientation 转竖屏（configChanges 声明了 orientation 不重建），
    // 一次性 doOnLayout 会把横屏宽算出的列数留在竖屏上。
    // 注意 recompute 里会替换 layoutManager，必须 post 出去，不能在 layout 过程中同步改。
    recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> recycler.post { recompute() } }
    recycler.post { recompute() }
}
