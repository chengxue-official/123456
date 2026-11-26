package com.houg.tv

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.houg.tv.net.EndpointConfig
import kotlin.concurrent.thread

/**
 * 接口自检页（测试版）：不用 adb，遥控器/手指点开就能看。
 * 显示：配置版本 + 来源（assets/cache/remote）+ min_client + 每个接口连通性。
 */
class EndpointDiagActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var results: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        val title = TextView(this).apply {
            text = "接口自检"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        header = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FFD166"))
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRefresh = Button(this).apply { text = "重新拉配置" }
        val btnRun = Button(this).apply { text = "重跑自检" }
        val btnBack = Button(this).apply { text = "返回" }
        btnRow.addView(btnRefresh)
        btnRow.addView(btnRun)
        btnRow.addView(btnBack)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }

        root.addView(title)
        root.addView(header)
        root.addView(summary)
        root.addView(btnRow)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener {
            summary.text = "正在重新拉取配置…"
            EndpointConfig.load(this, true)
            EndpointConfig.refreshRemoteAsync(this)
            header.postDelayed({ refreshHeader(); summary.text = "" }, 1500)
        }
        btnRun.setOnClickListener { runTests() }

        EndpointConfig.load(this)
        EndpointConfig.refreshRemoteAsync(this)
        refreshHeader()
        runTests()
        btnRun.requestFocus()
    }

    private fun refreshHeader() {
        header.text = "配置 v${EndpointConfig.version()} · 来源 ${EndpointConfig.source()} · min_client=${EndpointConfig.minClient()}"
    }

    private fun runTests() {
        results.removeAllViews()
        summary.text = "自检中…"
        val keys = EndpointConfig.keys()
        thread(name = "diag") {
            val rows = ArrayList<Triple<String, Boolean, String>>()
            var okCount = 0
            for (k in keys) {
                val r = EndpointConfig.probe(k)
                if (r.ok) okCount++
                rows.add(Triple(k, r.ok, r.toString()))
            }
            runOnUiThread {
                results.removeAllViews()
                for ((_, ok, text) in rows) {
                    addLine(text, if (ok) Color.parseColor("#7BD88F") else Color.parseColor("#FF6B6B"))
                }
                summary.text = "结果：$okCount/${keys.size} 可通" + if (okCount == keys.size) "  ✅" else "  ⚠️"
                refreshHeader()
            }
        }
    }

    private fun addLine(text: String, color: Int) {
        results.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
        })
    }
}
