package com.houg.tv.net

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * 接口自检（测试版）：启动时对 endpoints.json 里的每个接口发一次探针，
 * 把结果打到 logcat（tag=EpTest），用来判断"哪条路径被切了"。
 *
 * 触发方式：
 *   adb shell am start -n com.houg.tv/.MainActivity --ez eptest true
 *   adb logcat -s EpTest
 */
object EndpointSelfTest {

    private const val TAG = "EpTest"

    fun run(ctx: Context) {
        EndpointConfig.load(ctx, force = true)
        thread(name = "ep-selftest") {
            val keys = EndpointConfig.keys()
            Log.i(TAG, "=== self test start, config v${EndpointConfig.version()} from ${EndpointConfig.source()}, ${keys.size} endpoints ===")
            var ok = 0
            for (k in keys) {
                val r = EndpointConfig.probe(k)
                if (r.ok) ok++
                Log.i(TAG, (if (r.ok) "[OK]   " else "[FAIL] ") + r)
            }
            Log.i(TAG, "=== done: $ok/${keys.size} reachable ===")
            for (b in EndpointConfig.webBases()) {
                Log.i(TAG, "web fallback base: $b")
            }
            for (p in listOf("category", "rank", "detail", "search")) {
                EndpointConfig.webPath(p, "key" to "x", "board" to "hot", "id" to "0", "kw" to "test")
                    ?.let { Log.i(TAG, "web path[$p] = $it") }
            }
        }
    }
}
