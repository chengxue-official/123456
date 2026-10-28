package org.json;

/**
 * 仅编译期桩：search() 不读写磁盘，运行期不会走到这里。
 * 方法签名覆盖 DramaIndex.readFromDisk/writeToDisk 的用法。
 */
public class JSONObject {
    public JSONObject() {
    }

    public JSONObject(String source) throws JSONException {
    }

    public int optInt(String key, int fallback) {
        return fallback;
    }

    public long optLong(String key, long fallback) {
        return fallback;
    }

    public JSONArray optJSONArray(String key) {
        return null;
    }

    public JSONObject optJSONObject(String key) {
        return null;
    }

    public String optString(String key) {
        return "";
    }

    public JSONObject put(String key, Object value) {
        return this;
    }

    @Override
    public String toString() {
        return "{}";
    }
}
