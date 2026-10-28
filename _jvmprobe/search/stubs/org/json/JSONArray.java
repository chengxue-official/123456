package org.json;

/**
 * 仅编译期桩：search() 不读写磁盘，运行期不会走到这里。
 * 方法签名覆盖 DramaIndex.readFromDisk/writeToDisk 的用法。
 */
public class JSONArray {
    public JSONArray() {
    }

    public int length() {
        return 0;
    }

    public JSONObject optJSONObject(int index) {
        return null;
    }

    public JSONArray put(Object value) {
        return this;
    }
}
