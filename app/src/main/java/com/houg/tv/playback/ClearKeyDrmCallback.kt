package com.houg.tv.playback

import android.util.Base64
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 静态 ClearKey 回调：把 HG 接口下发的 CENC-AES-128 密钥直接喂给系统 ClearKey DRM。
 *
 * 背景：加密片源原先必须「整集下载 + 本地解密重封装」，起播慢且老设备容易 OOM。
 * 改为流播时用系统 ClearKey（API 21+ 自带）按 CENC senc 规范解密，密钥不走网络，
 * 这里直接在响应里回填明文 key，省去 HttpMediaDrmCallback 的许可服务器依赖。
 *
 * 协议：ClearKey 的 key request 体是 {"kids":["<kid-base64url>", ...]}，
 * 应答为 {"keys":[{"kty":"oct","kid":"...","k":"<key-base64url>"}]}。
 * 同一个 key 填给每个 kid 即可（HG 每集一个密钥，kid 只是 DRM 容器里的标识）。
 */
class ClearKeyDrmCallback(private val key: ByteArray) : MediaDrmCallback {

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val kids = try {
            JSONObject(String(request.data, Charsets.UTF_8)).optJSONArray("kids") ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
        // BUG-118：kids 为空说明 key request 非法（PSSH 缺失或格式不支持）。此前用
        // 16 字节全零占位 KID 回一条记录，密钥会被登记在全零 KID 下，与样本真实 KID
        // 不匹配，解密照样失败，只是白等一轮 DRM 会话。这里直接失败，让 onPlayerError
        // 立刻回退整集下载+本地解密，日志里也能直接看到原因。
        if (kids.length() == 0) {
            throw IllegalStateException("ClearKey key request 缺少 kids，无法匹配密钥")
        }
        val keys = JSONArray()
        for (i in 0 until kids.length()) {
            val kid = kids.optString(i, "")
            if (kid.isEmpty()) continue
            keys.put(keyEntry(kid))
        }
        return JSONObject().put("keys", keys).toString().toByteArray(Charsets.UTF_8)
    }

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        // ClearKey 不需要置备（provision）；返回空体即可
        ByteArray(0)

    private fun keyEntry(kid: String): JSONObject =
        JSONObject().put("kty", "oct").put("kid", kid).put("k", b64Url(key))

    private fun b64Url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
